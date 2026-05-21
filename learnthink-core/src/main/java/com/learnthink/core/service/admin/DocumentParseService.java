package com.learnthink.core.service.admin;

import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档解析编排服务: PDF → Mineru → 按章拆分 → 章节MD上传OSS → 入库.
 * 支持异步提交 + 进度轮询.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseService {

    private final MineruService mineruService;
    private final AliOSSUtil ossUtil;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final BookInfoService bookInfoService;

    private static final long SIGNED_URL_EXPIRY_MS = 60 * 60 * 1000L; // 60min

    // ====== 异步解析任务跟踪 ======

    private final ConcurrentHashMap<String, ParseTask> parseTasks = new ConcurrentHashMap<>();

    @lombok.Data
    private static class ParseTask {
        private String taskId;
        private String mineruTaskId;
        private String ossPdfUrl;
        private String courseId;
        private String title;
        private String sourceDocId; // 源文档ID（PDF教材），用作子文档的parentId
        private String state;
        private int progress;
        private String message;
        private String error;
        private List<ChapterInfo> chapters;
        private String fullMdUrl;  // 完整MD的OSS URL

        ParseTask(String taskId, String mineruTaskId, String ossPdfUrl, String courseId, String title, String sourceDocId) {
            this.taskId = taskId;
            this.mineruTaskId = mineruTaskId;
            this.ossPdfUrl = ossPdfUrl;
            this.courseId = courseId;
            this.title = title;
            this.sourceDocId = sourceDocId;
            this.state = "pending";
            this.progress = 0;
            this.message = "任务已提交";
        }

        ParseTaskProgress toProgress() {
            ParseTaskProgress p = new ParseTaskProgress();
            p.setTaskId(taskId);
            p.setState(state);
            p.setProgress(progress);
            p.setMessage(message);
            p.setError(error);
            p.setChapters(chapters);
            p.setFullMdUrl(fullMdUrl);
            return p;
        }
    }

    /**
     * 异步提交PDF解析任务.
     *
     * @param ossPdfUrl   OSS上PDF的访问URL
     * @param courseId    所属课程ID
     * @param title       文档标题
     * @param sourceDocId 源文档ID（PDF的docId），拆分出的子文档会设此值为parentId
     * @return 内部跟踪taskId
     */
    public String submitParse(String ossPdfUrl, String courseId, String title, String sourceDocId) {
        // 1. 生成OSS签名URL供Mineru下载
        String signedUrl = ossUtil.getSignedUrlWithExpiry(ossPdfUrl, SIGNED_URL_EXPIRY_MS);
        log.info("Generated signed URL for PDF: {}", ossPdfUrl);

        // 2. 先用原始OSS URL尝试，不行再走已签名URL
        String mineruTaskId;
        try {
            mineruTaskId = mineruService.submitTask(ossPdfUrl);
            log.info("Mineru task submitted with raw URL, mineruTaskId: {}", mineruTaskId);
        } catch (Exception e) {
            log.warn("Raw URL failed ({}), trying signed URL", e.getMessage());
            mineruTaskId = mineruService.submitTask(signedUrl);
            log.info("Mineru task submitted with signed URL, mineruTaskId: {}", mineruTaskId);
        }

        // 3. 创建内部跟踪任务
        String taskId = UUID.randomUUID().toString();
        parseTasks.put(taskId, new ParseTask(taskId, mineruTaskId, ossPdfUrl, courseId, title, sourceDocId));

        return taskId;
    }

    /**
     * 查询解析任务进度.
     * 当Mineru返回done时，自动执行下载 → 提取 → 拆分 → 上传OSS → 入库.
     */
    public ParseTaskProgress getParseProgress(String taskId) {
        ParseTask task = parseTasks.get(taskId);
        if (task == null) {
            throw new RuntimeException("Parse task not found: " + taskId);
        }

        // 已终止状态直接返回缓存
        if ("completed".equals(task.state) || "failed".equals(task.state)) {
            return task.toProgress();
        }

        try {
            MineruTaskStatus mineruStatus = mineruService.getTaskStatus(task.mineruTaskId);
            String state = mineruStatus.getState();

            switch (state) {
                case "done" -> completeTask(task, mineruStatus.getFullZipUrl());
                case "failed" -> {
                    task.state = "failed";
                    task.error = mineruStatus.getErrMsg() != null ? mineruStatus.getErrMsg() : "解析失败";
                    task.message = task.error;
                    log.error("Mineru task {} failed: {}", task.mineruTaskId, task.error);
                }
                default -> {
                    task.state = state;
                    task.progress = mineruStatus.getProgress();
                    task.message = stateToMessage(state);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to poll Mineru task {}: {}", task.mineruTaskId, e.getMessage());
            // 保持上次状态，下次重试
        }

        return task.toProgress();
    }

    private String stateToMessage(String state) {
        return switch (state) {
            case "pending" -> "任务排队中...";
            case "running" -> "Mineru正在解析PDF...";
            case "converting" -> "正在转换格式...";
            default -> state;
        };
    }

    /**
     * Mineru解析完成: 下载 → 提取 → 拆分 → 上传OSS → 入库.
     */
    private void completeTask(ParseTask task, String fullZipUrl) {
        task.state = "downloading";
        task.message = "正在下载解析结果...";
        byte[] zipBytes = mineruService.downloadZip(fullZipUrl);
        task.progress = 30;

        task.state = "extracting";
        task.message = "正在提取Markdown...";
        String fullMarkdown = mineruService.extractMarkdown(zipBytes);
        task.progress = 50;

        task.state = "splitting";
        task.message = "正在拆分章节...";
        List<ChapterInfo> chapters = MarkdownSplitter.splitByH2(fullMarkdown);
        log.info("Split markdown into {} chapters", chapters.size());
        task.progress = 60;

        task.state = "saving";
        task.message = "正在保存章节到OSS和数据库...";

        // 保存完整MD（拆分前的全文），供后续查看，关联到源PDF
        saveFullMdDocument(task, fullMarkdown, task.sourceDocId);

        // 逐个章节: 上传MD到OSS → 入库，关联到源PDF
        for (ChapterInfo chapter : chapters) {
            uploadAndSaveChapter(task, chapter);
        }

        task.state = "completed";
        task.progress = 100;
        task.message = "解析完成，共 " + chapters.size() + " 个章节";
        task.chapters = chapters;
        // 自动提取书籍信息
        try {
            bookInfoService.extract(task.sourceDocId, fullMarkdown);
        } catch (Exception e) {
            log.warn("Failed to extract book info for document {}: {}", task.sourceDocId, e.getMessage());
        }
        log.info("Parse task {} completed with {} chapters", task.taskId, chapters.size());
    }

    /**
     * 保存Mineru解析出的完整Markdown到OSS + 数据库.
     */
    private void saveFullMdDocument(ParseTask task, String fullMarkdown, String parentId) {
        String mdFileName = "full-" + MarkdownSplitter.sanitizeFileName(task.title) + ".md";
        String ossDir = "kb/" + task.courseId + "/processed";
        try (ByteArrayInputStream mdStream = new ByteArrayInputStream(fullMarkdown.getBytes(StandardCharsets.UTF_8))) {
            String mdUrl = ossUtil.uploadStream(mdStream, mdFileName, ossDir);
            task.fullMdUrl = mdUrl;

            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setCourseId(task.courseId);
            doc.setTitle(task.title + "（全文）");
            doc.setSourceType("讲义");
            doc.setFilePath(mdUrl);
            doc.setParentId(parentId);
            doc.setCreatedAt(LocalDateTime.now());
            knowledgeDocumentMapper.insert(doc);

            log.info("Saved full MD document: {}, parentId: {}", mdUrl, parentId);
        } catch (Exception e) {
            log.error("Failed to save full MD document for {}", task.title, e);
            // 不中断主流程，章节保存继续
        }
    }

    /**
     * 通用: 上传单个章节MD到OSS → 入库.
     */
    private ChapterInfo saveChapter(String courseId, String parentId, ChapterInfo chapter) {
        String mdFileName = MarkdownSplitter.sanitizeFileName(chapter.getTitle());
        String ossDir = "kb/" + courseId + "/processed";

        try (ByteArrayInputStream mdStream = new ByteArrayInputStream(chapter.getContent().getBytes())) {
            String mdUrl = ossUtil.uploadStream(mdStream, mdFileName, ossDir);
            chapter.setOssUrl(mdUrl);
        } catch (Exception e) {
            throw new RuntimeException("章节MD上传OSS失败: " + chapter.getTitle(), e);
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setCourseId(courseId);
        doc.setTitle(chapter.getTitle());
        doc.setSourceType("讲义章节");
        doc.setFilePath(chapter.getOssUrl());
        doc.setParentId(parentId);
        doc.setCreatedAt(LocalDateTime.now());
        knowledgeDocumentMapper.insert(doc);

        return chapter;
    }

    private void uploadAndSaveChapter(ParseTask task, ChapterInfo chapter) {
        saveChapter(task.courseId, task.sourceDocId, chapter);
        log.info("Uploaded chapter MD to OSS: {}", chapter.getOssUrl());
    }

    // ====== MD文档拆分（直接上传的完整MD → 按章拆分） ======

    /**
     * 拆分直接上传的MD教材文件为章节，每章上传OSS + 入库.
     *
     * @param ossMdUrl    完整MD在OSS上的URL
     * @param courseId    所属课程ID
     * @param sourceDocId 源文档ID（MD的docId），拆分出的子文档会设此值为parentId
     * @return 拆分出的章节列表
     */
    public List<ChapterInfo> splitMdDocument(String ossMdUrl, String courseId, String sourceDocId) {
        log.info("Splitting MD document: {} (course: {})", ossMdUrl, courseId);

        // 1. 从OSS下载完整MD
        String fullMarkdown;
        try (InputStream in = ossUtil.downloadFile(ossMdUrl)) {
            fullMarkdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("下载MD文件失败: " + e.getMessage(), e);
        }
        log.info("Downloaded MD, {} chars", fullMarkdown.length());

        // 2. 按章拆分
        List<ChapterInfo> chapters = MarkdownSplitter.split(fullMarkdown);
        log.info("Split into {} chapters", chapters.size());

        // 3. 逐个章节保存，关联到源MD文档
        for (ChapterInfo chapter : chapters) {
            saveChapter(courseId, sourceDocId, chapter);
        }

        // 自动提取书籍信息
        try {
            bookInfoService.extract(sourceDocId, fullMarkdown);
        } catch (Exception e) {
            log.warn("Failed to extract book info for document {}: {}", sourceDocId, e.getMessage());
        }

        return chapters;
    }

    // ====== 同步解析（向后兼容） ======

    /**
     * 同步解析PDF（旧接口，阻塞直到完成）.
     */
    @Transactional
    public List<ChapterInfo> parsePdf(String ossPdfUrl, String courseId, String title) {
        String signedUrl = ossUtil.getSignedUrlWithExpiry(ossPdfUrl, SIGNED_URL_EXPIRY_MS);
        log.info("Generated signed URL for PDF: {}", ossPdfUrl);

        String fullMarkdown = mineruService.parseDocument(signedUrl);
        log.info("Mineru parsed PDF, got {} chars of markdown", fullMarkdown.length());

        List<ChapterInfo> chapters = MarkdownSplitter.splitByH2(fullMarkdown);
        log.info("Split markdown into {} chapters", chapters.size());

        KnowledgeDocument pdfDoc = new KnowledgeDocument();
        pdfDoc.setCourseId(courseId);
        pdfDoc.setTitle(title);
        pdfDoc.setSourceType("教材");
        pdfDoc.setFilePath(ossPdfUrl);
        pdfDoc.setCreatedAt(LocalDateTime.now());
        knowledgeDocumentMapper.insert(pdfDoc);
        String pdfDocId = pdfDoc.getId(); // 获取新生成的PDF文档ID作为parentId

        for (ChapterInfo chapter : chapters) {
            saveChapter(courseId, pdfDocId, chapter);
        }

        return chapters;
    }
}
