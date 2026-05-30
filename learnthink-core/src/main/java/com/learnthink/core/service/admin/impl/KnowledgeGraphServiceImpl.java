package com.learnthink.core.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.admin.KnowledgeGraphResult;
import com.learnthink.core.agent.impl.EvidenceRetriever.RagClient;
import com.learnthink.core.agent.impl.EvidenceRetriever.RagClient.RagResponse;
import com.learnthink.core.agent.impl.EvidenceRetriever.RagClient.SourceRef;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.domain.entity.BookInfo;
import com.learnthink.core.domain.entity.Course;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.BookInfoMapper;
import com.learnthink.core.repository.CourseMapper;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import com.learnthink.core.service.KpExtractionService;
import com.learnthink.core.service.admin.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphServiceImpl implements KnowledgeGraphService {

    private static final int QUERY_COUNT = 6;
    private static final int RAG_K = 5;
    private static final double RAG_MIN_RELEVANCE = 0.2;
    private static final int RAG_MIN_SOURCES = 1;
    private static final int MAX_RAG_EXCERPT_LENGTH = 300;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final BookInfoMapper bookInfoMapper;
    private final CourseMapper courseMapper;
    private final RagClient ragClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final KpExtractionService kpExtractionService;

    @Qualifier("reasoningChatClientBuilder")
    private final ChatClient.Builder chatClientBuilder;

    @Override
    public KnowledgeGraphResult get(String courseId) {
        BookInfo bookInfo = findPrimaryTextbook(courseId);
        if (bookInfo.getKnowledgeGraph() == null || bookInfo.getKnowledgeGraph().isBlank()) {
            throw new RuntimeException("该课程尚未生成知识图谱，请先生成");
        }
        Object graph = parseAndValidateGraph(bookInfo.getKnowledgeGraph());
        return new KnowledgeGraphResult(
            courseId, getCourseName(courseId), bookInfo.getTitle(), graph,
            List.of(), 0,
            bookInfo.getExtractedAt() != null
                ? bookInfo.getExtractedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : "");
    }

    @Override
    public void save(String courseId, Map<String, Object> graphData) {
        BookInfo bookInfo = findPrimaryTextbook(courseId);
        String json;
        try {
            json = objectMapper.writeValueAsString(graphData);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("图谱数据序列化失败: " + e.getMessage());
        }
        parseAndValidateGraph(json);
        bookInfo.setKnowledgeGraph(json);
        bookInfoMapper.updateById(bookInfo);
        log.info("[KnowledgeGraph] Saved graph for course {}: {} nodes",
            courseId, ((List<?>) graphData.get("nodes")).size());
    }

    @Override
    public KnowledgeGraphResult generate(String courseId) {
        // Step 1: 获取主教材的 BookInfo
        BookInfo bookInfo = findPrimaryTextbook(courseId);

        // Step 2: AI 生成检索查询词
        List<String> queries = generateSearchQueries(bookInfo);

        // Step 3: RAG 批量检索
        List<SourceRef> ragSources = retrieveFromRag(courseId, queries);
        String ragContent = formatRagContent(ragSources);

        // Step 4: AI 生成知识图谱
        String graphJson = generateGraph(bookInfo, ragContent);
        Object graph = parseAndValidateGraph(graphJson);

        // 回写 book_info
        bookInfo.setKnowledgeGraph(graphJson);
        bookInfoMapper.updateById(bookInfo);

        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return new KnowledgeGraphResult(
            courseId, getCourseName(courseId), bookInfo.getTitle(), graph, queries, ragSources.size(), generatedAt);
    }

    @Override
    public KnowledgeGraphResult generateKpTree(String courseId) {
        log.info("[KpTree] Starting KP tree generation for course {}", courseId);

        // Step 1: 获取主教材
        BookInfo bookInfo;
        try {
            bookInfo = findPrimaryTextbook(courseId);
            log.info("[KpTree] Found textbook: {}", bookInfo.getTitle());
        } catch (RuntimeException e) {
            log.error("[KpTree] Step 1 failed - findPrimaryTextbook: {}", e.getMessage());
            throw e;
        }

        // Step 2: AI 生成检索查询词
        List<String> queries;
        try {
            queries = generateSearchQueries(bookInfo);
            log.info("[KpTree] Generated {} search queries", queries.size());
        } catch (Exception e) {
            log.error("[KpTree] Step 2 failed - generateSearchQueries: {}", e.getMessage(), e);
            throw new RuntimeException("生成检索查询词失败: " + e.getMessage(), e);
        }

        // Step 3: RAG 批量检索
        List<SourceRef> ragSources;
        try {
            ragSources = retrieveFromRag(courseId, queries);
            log.info("[KpTree] RAG retrieved {} sources", ragSources.size());
        } catch (Exception e) {
            log.error("[KpTree] Step 3 failed - RAG retrieval: {}", e.getMessage(), e);
            throw new RuntimeException("RAG 检索失败: " + e.getMessage(), e);
        }
        String ragContent = formatRagContent(ragSources);

        // Step 4: AI 生成知识点树
        String treeJson;
        try {
            treeJson = generateKpTreeJson(bookInfo, ragContent);
        } catch (Exception e) {
            log.error("[KpTree] Step 4 failed - LLM generation: {}", e.getMessage(), e);
            throw new RuntimeException("AI 生成知识点树失败: " + e.getMessage(), e);
        }

        // Step 5: 校验 JSON
        try {
            parseAndValidateKpTree(treeJson);
        } catch (RuntimeException e) {
            log.error("[KpTree] Step 5 failed - validation: {}", e.getMessage());
            throw e;
        }

        // Step 6: 持久化
        try {
            bookInfo.setKpTree(treeJson);
            bookInfoMapper.updateById(bookInfo);
            kpExtractionService.saveTreeFromJson(treeJson, courseId);
            log.info("[KpTree] Persisted KP tree to book_info and course_knowledge_points");
        } catch (Exception e) {
            log.error("[KpTree] Step 6 failed - persistence: {}", e.getMessage(), e);
            throw new RuntimeException("保存知识点树失败: " + e.getMessage(), e);
        }

        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("[KpTree] KP tree generation complete for course {}: {} queries, {} RAG sources",
            courseId, queries.size(), ragSources.size());

        return new KnowledgeGraphResult(
            courseId, getCourseName(courseId), bookInfo.getTitle(), null, queries, ragSources.size(), generatedAt);
    }

    // ====== Step 1: 查找主教材 ======

    private static final String PRIMARY_TEXTBOOK_TYPE = "主教材";

    private BookInfo findPrimaryTextbook(String courseId) {
        List<String> docIds = knowledgeDocumentMapper.selectList(
            new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getCourseId, courseId)
                .eq(KnowledgeDocument::getSourceType, PRIMARY_TEXTBOOK_TYPE)
        ).stream().map(KnowledgeDocument::getId).toList();

        if (docIds.isEmpty()) {
            log.error("[KnowledgeGraph] No primary textbook found for course {} (sourceType={})",
                courseId, PRIMARY_TEXTBOOK_TYPE);
            throw new RuntimeException("该课程暂无主教材（sourceType=主教材），请先在资料管理页上传主教材");
        }

        BookInfo bookInfo = bookInfoMapper.selectOne(
            new LambdaQueryWrapper<BookInfo>().in(BookInfo::getDocumentId, docIds));
        if (bookInfo == null) {
            log.error("[KnowledgeGraph] BookInfo not extracted for course {}, docIds={}", courseId, docIds);
            throw new RuntimeException("主教材信息尚未提取，请先在资料管理页执行提取操作");
        }
        if (bookInfo.getIntroduction() == null || bookInfo.getIntroduction().isBlank()) {
            log.error("[KnowledgeGraph] BookInfo missing introduction for course {}", courseId);
            throw new RuntimeException("主教材缺少内容简介，请确认资料提取是否完整");
        }
        if (bookInfo.getToc() == null || bookInfo.getToc().isBlank() || "[]".equals(bookInfo.getToc().strip())) {
            log.error("[KnowledgeGraph] BookInfo missing toc for course {}", courseId);
            throw new RuntimeException("主教材缺少目录信息，请确认资料提取是否完整");
        }
        return bookInfo;
    }

    // ====== Step 2: AI 生成检索查询 ======

    private List<String> generateSearchQueries(BookInfo bookInfo) {
        String prompt = promptLoader.get("knowledge_graph/search_queries")
            .replace("{queryCount}", String.valueOf(QUERY_COUNT))
            .replace("{title}", bookInfo.getTitle())
            .replace("{author}", bookInfo.getAuthor() != null ? bookInfo.getAuthor() : "未知")
            .replace("{introduction}", truncate(bookInfo.getIntroduction(), 2000))
            .replace("{toc}", truncate(bookInfo.getToc(), 3000));

        String response = chatClientBuilder.build().prompt()
            .messages(new SystemMessage(prompt), new UserMessage("请生成检索查询词"))
            .call().content();

        log.info("[KnowledgeGraph] search queries response: {}", response);
        return parseJsonArray(response);
    }

    // ====== Step 3: RAG 检索 ======

    private List<SourceRef> retrieveFromRag(String courseId, List<String> queries) {
        Map<String, SourceRef> seen = new LinkedHashMap<>();
        for (String query : queries) {
            try {
                RagResponse resp = ragClient.retrieve(courseId, query, "",
                    RAG_K, RAG_MIN_RELEVANCE, RAG_MIN_SOURCES);
                if (resp != null && resp.sources() != null) {
                    for (SourceRef s : resp.sources()) {
                        String key = s.chunkId() != null && !s.chunkId().isBlank()
                            ? s.chunkId() : s.docId() + "|" + s.quote().hashCode();
                        seen.putIfAbsent(key, s);
                    }
                }
            } catch (Exception e) {
                log.warn("[KnowledgeGraph] RAG query failed for '{}': {}", query, e.getMessage());
            }
        }
        log.info("[KnowledgeGraph] RAG retrieved {} unique sources from {} queries", seen.size(), queries.size());
        return new ArrayList<>(seen.values());
    }

    private String formatRagContent(List<SourceRef> sources) {
        if (sources.isEmpty()) return "（未检索到相关内容）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            SourceRef s = sources.get(i);
            sb.append("[").append(i + 1).append("] ");
            if (s.bookTitle() != null && !s.bookTitle().isBlank()) {
                sb.append("《").append(s.bookTitle()).append("》");
            }
            if (s.chapterTitle() != null && !s.chapterTitle().isBlank()) {
                sb.append(" ").append(s.chapterTitle());
            }
            sb.append("\n  ").append(truncate(s.quote(), MAX_RAG_EXCERPT_LENGTH)).append("\n\n");
        }
        return sb.toString();
    }

    // ====== Step 4: AI 生成知识图谱 ======

    private String generateGraph(BookInfo bookInfo, String ragContent) {
        String prompt = promptLoader.get("knowledge_graph/generate_graph")
            .replace("{title}", bookInfo.getTitle())
            .replace("{introduction}", truncate(bookInfo.getIntroduction(), 2000))
            .replace("{toc}", truncate(bookInfo.getToc(), 3000))
            .replace("{ragContent}", ragContent);

        String response = chatClientBuilder.build().prompt()
            .messages(new SystemMessage(prompt),
                new UserMessage("请生成知识图谱"))
            .call().content();

        log.info("[KnowledgeGraph] graph response length: {} chars",
            response != null ? response.length() : 0);
        return stripMarkdownFence(response);
    }

    // ====== Step 4b: AI 生成知识点树 ======

    private String generateKpTreeJson(BookInfo bookInfo, String ragContent) {
        String prompt = promptLoader.get("knowledge_graph/generate_kp_tree")
            .replace("{title}", bookInfo.getTitle())
            .replace("{introduction}", truncate(bookInfo.getIntroduction(), 2000))
            .replace("{toc}", truncate(bookInfo.getToc(), 3000))
            .replace("{ragContent}", ragContent);

        String response = chatClientBuilder.build().prompt()
            .messages(new SystemMessage(prompt),
                new UserMessage("请生成知识点树"))
            .call().content();

        log.info("[KnowledgeGraph] KP tree response length: {} chars",
            response != null ? response.length() : 0);
        return stripMarkdownFence(response);
    }

    @SuppressWarnings("unchecked")
    private void parseAndValidateKpTree(String treeJson) {
        Map<String, Object> tree;
        try {
            tree = objectMapper.readValue(treeJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("AI 生成的知识点树 JSON 格式无效: " + e.getMessage());
        }

        if (!tree.containsKey("name")) {
            throw new RuntimeException("知识点树缺少根节点 name 字段");
        }
        if (!tree.containsKey("children") || !(tree.get("children") instanceof List)) {
            throw new RuntimeException("知识点树缺少 children 数组");
        }

        // 递归统计节点数
        int[] count = new int[1];
        countNodes(tree, count);
        if (count[0] < 10) {
            throw new RuntimeException("知识点树节点数过少（" + count[0] + "），请检查教材内容是否完整");
        }
        log.info("[KnowledgeGraph] KP tree validated: {} total nodes", count[0]);
    }

    @SuppressWarnings("unchecked")
    private void countNodes(Map<String, Object> node, int[] count) {
        count[0]++;
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        if (children != null) {
            for (Map<String, Object> child : children) {
                countNodes(child, count);
            }
        }
    }

    // ====== JSON 处理 ======

    @SuppressWarnings("unchecked")
    private Object parseAndValidateGraph(String graphJson) {
        Map<String, Object> graph;
        try {
            graph = objectMapper.readValue(graphJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("AI 生成的知识图谱 JSON 格式无效: " + e.getMessage());
        }

        if (!graph.containsKey("nodes") || !(graph.get("nodes") instanceof List)) {
            throw new RuntimeException("知识图谱缺少 nodes 数组");
        }
        if (!graph.containsKey("edges") || !(graph.get("edges") instanceof List)) {
            throw new RuntimeException("知识图谱缺少 edges 数组");
        }

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        Set<String> nodeIds = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            Object id = node.get("id");
            if (id == null) throw new RuntimeException("节点缺少 id 字段");
            nodeIds.add(id.toString());
            if (node.get("label") == null) throw new RuntimeException("节点 " + id + " 缺少 label 字段");
        }

        for (Map<String, Object> edge : edges) {
            Object source = edge.get("source");
            Object target = edge.get("target");
            if (source == null || target == null)
                throw new RuntimeException("边缺少 source 或 target 字段");
            if (!nodeIds.contains(source.toString()))
                throw new RuntimeException("边引用了不存在的 source 节点: " + source);
            if (!nodeIds.contains(target.toString()))
                throw new RuntimeException("边引用了不存在的 target 节点: " + target);
        }

        log.info("[KnowledgeGraph] Validated: {} nodes, {} edges", nodes.size(), edges.size());
        return graph;
    }

    private List<String> parseJsonArray(String raw) {
        String json = extractJson(raw);
        if (json.isEmpty()) {
            log.warn("[KnowledgeGraph] No JSON array found in AI response");
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] Failed to parse search queries JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 AI 返回的文本中提取 JSON，处理以下情况：
     * 1. 纯 JSON
     * 2. ```json ... ``` 或 ``` ... ``` 包裹
     * 3. 前后有额外文字 + 代码块包裹
     * 4. 前后有额外文字、无代码块（通过 {} 或 [] 定位）
     */
    private String extractJson(String raw) {
        if (raw == null) return "";

        // 优先提取 markdown 代码块中的内容
        Pattern fencePattern = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)```");
        Matcher fenceMatcher = fencePattern.matcher(raw);
        if (fenceMatcher.find()) {
            return fenceMatcher.group(1).trim();
        }

        // 没有代码块 — 尝试定位最外层 { } 或 [ ]
        return extractOuterBraces(raw);
    }

    private String extractOuterBraces(String raw) {
        String s = raw.trim();
        // 找第一个 { 或 [
        int start = -1;
        char startChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                startChar = c;
                break;
            }
        }
        if (start < 0) return "";

        char endChar = (startChar == '{') ? '}' : ']';
        int depth = 0;
        int end = -1;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == startChar) depth++;
            else if (c == endChar) {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) return "";
        return s.substring(start, end + 1).trim();
    }

    private String stripMarkdownFence(String raw) {
        return extractJson(raw);
    }

    // ====== 工具方法 ======

    private String getCourseName(String courseId) {
        Course course = courseMapper.selectById(courseId);
        return course != null ? course.getName() : "";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
