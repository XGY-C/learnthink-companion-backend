package com.learnthink.core.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.domain.entity.BookInfo;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.BookInfoMapper;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import com.learnthink.core.service.admin.BookInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookInfoServiceImpl implements BookInfoService {

    private final BookInfoMapper bookInfoMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final AliOSSUtil ossUtil;

    @Override
    public BookInfo getByDocumentId(String documentId) {
        return bookInfoMapper.selectOne(
                new LambdaQueryWrapper<BookInfo>()
                        .eq(BookInfo::getDocumentId, documentId)
        );
    }

    @Override
    @Transactional
    public BookInfo extract(String documentId, String fullMarkdown) {
        // 查询父文档
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + documentId);
        }

        // 提取作者
        String author = extractAuthor(fullMarkdown);
        log.info("Extracted author: '{}' for document {}", author, documentId);

        // 提取内容简介
        String introduction = extractIntroduction(fullMarkdown);
        log.info("Extracted introduction ({} chars) for document {}", introduction.length(), documentId);

        // 从拆分出的"目录"章节文件获取层级目录，失败则回退到MD标题解析
        String toc = buildTocFromChildDoc(documentId);
        if (toc == null || toc.isEmpty() || "[]".equals(toc)) {
            toc = buildTocFromMarkdown(fullMarkdown);
            log.info("TOC child doc not found, fell back to heading parsing for doc {}", documentId);
        } else {
            log.info("Built TOC from child doc for document {}", documentId);
        }

        // 删除旧记录（如有）
        bookInfoMapper.delete(new LambdaQueryWrapper<BookInfo>()
                .eq(BookInfo::getDocumentId, documentId));

        // 保存
        BookInfo info = new BookInfo();
        info.setDocumentId(documentId);
        info.setTitle(doc.getTitle());
        info.setAuthor(author);
        info.setIntroduction(introduction);
        info.setToc(toc);
        info.setExtractedAt(LocalDateTime.now());
        bookInfoMapper.insert(info);

        log.info("BookInfo saved for document {}: author='{}'", documentId, author);
        return info;
    }

    @Override
    public BookInfo extractFromOss(String documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null || doc.getFilePath() == null || doc.getFilePath().isBlank()) {
            throw new RuntimeException("文档不存在或没有文件路径: " + documentId);
        }

        String fullMarkdown;
        try (InputStream in = ossUtil.downloadFile(doc.getFilePath())) {
            fullMarkdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("从OSS下载MD失败: " + e.getMessage(), e);
        }

        return extract(documentId, fullMarkdown);
    }

    @Override
    public BookInfo update(String documentId, String author, String introduction) {
        BookInfo info = bookInfoMapper.selectOne(
                new LambdaQueryWrapper<BookInfo>().eq(BookInfo::getDocumentId, documentId));
        if (info == null) {
            throw new RuntimeException("书籍信息不存在，请先提取: " + documentId);
        }
        if (author != null) info.setAuthor(author);
        if (introduction != null) info.setIntroduction(introduction);
        info.setExtractedAt(LocalDateTime.now());
        bookInfoMapper.updateById(info);
        log.info("BookInfo updated for document {}", documentId);
        return info;
    }

    // ====== 作者提取 ======

    /** 在前30行中匹配常见的作者格式 */
    private static final Pattern[] AUTHOR_PATTERNS = {
            Pattern.compile("作者[：:]\\s*(.+)"),
            Pattern.compile("^\\s*(.{2,20}?)(?:编[著写]|著|编著)\\s*$"),
    };

    String extractAuthor(String markdown) {
        String[] lines = markdown.split("\n", 30);
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isBlank()) continue;
            // 跳过标题行（# 开头）
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) continue;

            for (Pattern p : AUTHOR_PATTERNS) {
                Matcher m = p.matcher(trimmed);
                if (m.find()) {
                    String author = m.group(1).strip();
                    if (!author.isBlank() && author.length() <= 30) {
                        return author;
                    }
                }
            }
        }
        return "";
    }

    // ====== 内容简介提取 ======

    /** 按优先级匹配的标题关键词 */
    private static final String[] INTRO_HEADINGS = {"内容提要", "内容简介", "前言", "序言"};

    String extractIntroduction(String markdown) {
        String best = "";

        for (String heading : INTRO_HEADINGS) {
            // 匹配 # 内容提要 或 ## 内容提要 等
            Pattern p = Pattern.compile("^(#{1,4})\\s*" + Pattern.quote(heading) + "\\s*$", Pattern.MULTILINE);
            Matcher m = p.matcher(markdown);
            if (!m.find()) continue;

            int start = m.end(); // 标题行之后的内容开始
            int headingLevel = m.group(1).length(); // # 的个数

            // 找下一个同级别或更高级别标题作为结束
            Pattern nextHeading = Pattern.compile("^#{1," + headingLevel + "}\\s+", Pattern.MULTILINE);
            Matcher next = nextHeading.matcher(markdown);
            int end = markdown.length();
            if (next.find(start)) {
                end = next.start();
            }

            String content = markdown.substring(start, end).strip();
            if (content.length() > 20) {
                best = content;
                break; // 高优先级匹配到了，直接返回
            }
        }

        return best;
    }

    // ====== 目录构建（优先从拆分出的"目录"章节文件，失败回退到MD标题解析） ======

    /** 从子文档中查找"目录"章节，下载并解析层级目录 */
    String buildTocFromChildDoc(String documentId) {
        // 查找标题包含"目录"的子文档
        List<KnowledgeDocument> children = knowledgeDocumentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getParentId, documentId)
                        .like(KnowledgeDocument::getTitle, "目录")
        );
        if (children.isEmpty()) return null;

        String filePath = children.get(0).getFilePath();
        if (filePath == null || filePath.isBlank()) return null;

        // 从OSS下载目录章节内容
        String content;
        try (InputStream in = ossUtil.downloadFile(filePath)) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to download TOC file {}: {}", filePath, e.getMessage());
            return null;
        }

        return parseTocContent(content);
    }

    /** 解析目录章节内容，依据标题编号识别层级（第X章=1, X.Y=2, X.Y.Z=3） */
    String parseTocContent(String content) {
        String[] lines = content.split("\n");
        List<LineEntry> entries = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isBlank()) continue;

            // 跳过"目录"章节本身的标题行
            String stripped = trimmed.replaceAll("^#+\\s*", "").strip();
            if (stripped.equals("目录") || stripped.equals("目录　")) continue;

            // 去掉 # 前缀
            String text = trimmed.startsWith("#") ?
                    trimmed.replaceAll("^#+\\s*", "") : trimmed;
            text = cleanupTocTitle(text).strip();
            if (text.isBlank()) continue;

            // 依据编号识别层级，而非缩进
            int level = detectTocLevel(text);
            entries.add(new LineEntry(text, level));
        }

        if (entries.isEmpty()) return "[]";

        // 栈构建树
        Deque<List<Map<String, Object>>> stack = new ArrayDeque<>();
        List<Map<String, Object>> root = new ArrayList<>();
        stack.push(root);

        for (LineEntry entry : entries) {
            int level = Math.min(Math.max(entry.indent, 1), 3);
            while (stack.size() > level) stack.pop();

            List<Map<String, Object>> children = new ArrayList<>();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("title", entry.text);
            node.put("children", children);

            stack.peek().add(node);
            stack.push(children);
        }

        return toJsonTree(root);
    }

    /** 从标题文本中依据编号层级识别目录层级（1→1, 1.1→2, 1.1.1→3；第X章→1；默认→2） */
    int detectTocLevel(String text) {
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\b").matcher(text);
        if (m.find()) {
            return m.group(1).split("\\.").length;
        }
        if (text.contains("章") || text.contains("节")) {
            return 1;
        }
        return 2;
    }

    /** 清理目录行：去掉列表标记、链接、引导点、页码 */
    static String cleanupTocTitle(String line) {
        // 去掉 Markdown 列表标记（- / * / + / 1.）
        line = line.replaceAll("^[-*+]\\s+", "");
        line = line.replaceAll("^\\d+\\.\\s+", "");
        // 去掉 Markdown 链接 [显示文本](url)
        line = line.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        // 去掉末尾引导点……和页码  如 "第1章 绪论.................. 1"
        line = line.replaceAll("\\s*[.．·…]+\\s*\\d*\\s*$", "");
        // 去掉末尾页码数字
        line = line.replaceAll("\\s+\\d+$", "");
        return line.strip();
    }

    private static class LineEntry {
        final String text;
        final int indent;
        LineEntry(String text, int indent) { this.text = text; this.indent = indent; }
    }

    // ====== 回退方案：从MD标题解析H1/H2/H3 ======

    /** 从完整Markdown中提取H1~H3标题，构建层级目录树JSON */
    String buildTocFromMarkdown(String markdown) {
        Pattern pattern = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(markdown);

        Deque<List<Map<String, Object>>> stack = new ArrayDeque<>();
        List<Map<String, Object>> root = new ArrayList<>();
        stack.push(root);

        while (matcher.find()) {
            int level = matcher.group(1).length();
            String title = matcher.group(2).strip();
            while (stack.size() > level) stack.pop();

            List<Map<String, Object>> children = new ArrayList<>();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("title", title);
            node.put("children", children);

            stack.peek().add(node);
            stack.push(children);
        }

        return toJsonTree(root);
    }

    // ====== JSON序列化工具 ======

    private String toJsonTree(List<Map<String, Object>> nodes) {
        if (nodes == null || nodes.isEmpty()) return "[]";
        return nodes.stream().map(this::nodeToJson).collect(Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings("unchecked")
    private String nodeToJson(Map<String, Object> node) {
        String title = jsonEscape((String) node.get("title"));
        String children = toJsonTree((List<Map<String, Object>>) node.get("children"));
        return "{\"title\":\"" + title + "\",\"children\":" + children + "}";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
