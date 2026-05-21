package com.learnthink.web.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import com.learnthink.core.service.admin.BookInfoService;
import com.learnthink.core.service.admin.ChapterInfo;
import com.learnthink.core.service.admin.DocumentParseService;
import com.learnthink.core.service.admin.ParseTaskProgress;
import com.learnthink.web.rag.RagIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端 — 文档管理 (PDF上传 + Mineru解析 + RAG知识库摄入).
 */
@RestController
@RequestMapping("/admin/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final AliOSSUtil ossUtil;
    private final DocumentParseService documentParseService;
    private final RagIngestService ragIngestService;
    private final BookInfoService bookInfoService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 上传PDF到OSS（仅存储，不解析）.
     */
    @PostMapping("/upload-pdf")
    public Result<Map<String, Object>> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("courseId") String courseId,
            @RequestParam(value = "title", defaultValue = "") String title) {

        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            return Result.error(400, "仅支持 PDF 文件");
        }

        // Mineru精准解析API限制: 文件≤200MB, 页数≤200
        if (file.getSize() > 200 * 1024 * 1024) {
            return Result.error(400, "PDF文件不能超过 200MB（Mineru精准解析API限制）");
        }

        try {
            String ossPath = "kb/" + courseId + "/pdf";
            String fileUrl = ossUtil.upload(file, ossPath);

            String docTitle = title.isBlank() ? originalFilename.replaceAll("(?i)\\.pdf$", "") : title;

            // 写入数据库记录，用于文档列表展示
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setCourseId(courseId);
            doc.setTitle(docTitle);
            doc.setSourceType("教材");
            doc.setFilePath(fileUrl);
            doc.setCreatedAt(LocalDateTime.now());
            knowledgeDocumentMapper.insert(doc);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", doc.getId());
            data.put("fileUrl", fileUrl);
            data.put("fileName", originalFilename);
            data.put("fileSize", file.getSize());
            data.put("courseId", courseId);
            data.put("title", docTitle);

            return Result.success(data, "上传成功");
        } catch (Exception e) {
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    /**
     * 上传教材MD到OSS（完整Markdown教材，与PDF同级，不需要Mineru解析）.
     */
    @PostMapping("/upload-md")
    public Result<Map<String, Object>> uploadMd(
            @RequestParam("file") MultipartFile file,
            @RequestParam("courseId") String courseId,
            @RequestParam(value = "title", defaultValue = "") String title) {

        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".md")) {
            return Result.error(400, "仅支持 Markdown (.md) 文件");
        }

        try {
            String ossPath = "kb/" + courseId + "/md";
            String fileUrl = ossUtil.upload(file, ossPath);

            String docTitle = title.isBlank() ? originalFilename.replaceAll("(?i)\\.md$", "") : title;

            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setCourseId(courseId);
            doc.setTitle(docTitle);
            doc.setSourceType("教材");
            doc.setFilePath(fileUrl);
            doc.setCreatedAt(LocalDateTime.now());
            knowledgeDocumentMapper.insert(doc);

            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("id", doc.getId());
            data.put("fileUrl", fileUrl);
            data.put("fileName", originalFilename);
            data.put("fileSize", file.getSize());
            data.put("courseId", courseId);
            data.put("title", docTitle);

            return Result.success(data, "上传成功");
        } catch (Exception e) {
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    /**
     * 异步提交PDF解析任务。立即返回taskId，前端通过 parse-task/{taskId} 轮询进度.
     */
    @PostMapping("/parse-submit")
    public Result<Map<String, Object>> submitParse(@RequestBody Map<String, String> body) {
        String fileUrl = body.get("fileUrl");
        String courseId = body.get("courseId");
        String title = body.getOrDefault("title", "未命名文档");
        String docId = body.get("id"); // 源文档ID，用作拆分后子文档的parentId

        if (fileUrl == null || fileUrl.isBlank()) {
            return Result.error(400, "fileUrl 不能为空");
        }
        if (courseId == null || courseId.isBlank()) {
            return Result.error(400, "courseId 不能为空");
        }

        try {
            String taskId = documentParseService.submitParse(fileUrl, courseId, title, docId);
            return Result.success(Map.of("taskId", taskId), "已提交解析任务");
        } catch (Exception e) {
            return Result.error(500, "提交解析任务失败: " + e.getMessage());
        }
    }

    /**
     * 拆分直接上传的MD教材（无需Mineru，直接按章拆分）.
     */
    @PostMapping("/split-md")
    public Result<Map<String, Object>> splitMd(@RequestBody Map<String, String> body) {
        String fileUrl = body.get("fileUrl");
        String courseId = body.get("courseId");
        String docId = body.get("id"); // 源文档ID，用作拆分后子文档的parentId

        if (fileUrl == null || fileUrl.isBlank()) {
            return Result.error(400, "fileUrl 不能为空");
        }
        if (courseId == null || courseId.isBlank()) {
            return Result.error(400, "courseId 不能为空");
        }

        try {
            List<ChapterInfo> chapters = documentParseService.splitMdDocument(fileUrl, courseId, docId);
            return Result.success(Map.of(
                "chapters", chapters,
                "count", chapters.size()
            ), "拆分完成，共 " + chapters.size() + " 个章节");
        } catch (Exception e) {
            return Result.error(500, "拆分MD失败: " + e.getMessage());
        }
    }

    /**
     * 查询解析任务进度。Mineru完成时会自动执行下载→提取→拆分→上传→入库.
     */
    @GetMapping("/parse-task/{taskId}")
    public Result<ParseTaskProgress> getParseTask(@PathVariable String taskId) {
        try {
            ParseTaskProgress progress = documentParseService.getParseProgress(taskId);
            return Result.success(progress);
        } catch (Exception e) {
            return Result.error(500, "查询解析进度失败: " + e.getMessage());
        }
    }

    /**
     * 文档列表（支持按课程筛选）.
     */
    @GetMapping
    public Result<List<KnowledgeDocument>> listDocuments(
            @RequestParam(required = false) String courseId) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(courseId != null && !courseId.isBlank(), KnowledgeDocument::getCourseId, courseId)
                .orderByDesc(KnowledgeDocument::getCreatedAt);
        return Result.success(knowledgeDocumentMapper.selectList(wrapper));
    }

    /**
     * 删除文档记录.
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteDocument(@PathVariable String id) {
        knowledgeDocumentMapper.deleteById(id);
        return Result.success(null, "已删除");
    }

    /**
     * 预览章节MD内容：从OSS读取原文并返回.
     */
    @GetMapping("/preview")
    public Result<Map<String, String>> previewChapter(@RequestParam String id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) {
            return Result.error(404, "文档不存在");
        }
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) {
            return Result.error(400, "文档无文件路径");
        }
        try (InputStream in = ossUtil.downloadFile(doc.getFilePath())) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String preview = content.length() > 5000 ? content.substring(0, 5000) + "\n\n...（内容过长，仅显示前5000字符）" : content;
            return Result.success(Map.of("content", preview, "title", doc.getTitle()));
        } catch (Exception e) {
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /**
     * 通过OSS URL直接预览MD内容（无需DB记录）.
     * 用于解析完成后在弹窗中查看完整MD.
     */
    @GetMapping("/preview-by-url")
    public Result<Map<String, String>> previewByUrl(@RequestParam String url) {
        try (InputStream in = ossUtil.downloadFile(url)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String preview = content.length() > 5000 ? content.substring(0, 5000) + "\n\n...（内容过长，仅显示前5000字符）" : content;
            return Result.success(Map.of("content", preview, "title", "完整MD"));
        } catch (Exception e) {
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    /**
     * 查询文档的书籍信息（作者、简介、目录）.
     * 未提取时返回 data=null，前端可按需调用重新提取.
     */
    @GetMapping("/{id}/book-info")
    public Result<com.learnthink.core.domain.entity.BookInfo> getBookInfo(@PathVariable String id) {
        return Result.success(bookInfoService.getByDocumentId(id));
    }

    /**
     * 手动更新书籍信息（修正自动提取的少量偏差）.
     */
    @PutMapping("/{id}/book-info")
    public Result<com.learnthink.core.domain.entity.BookInfo> updateBookInfo(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            com.learnthink.core.domain.entity.BookInfo info = bookInfoService.update(
                    id, body.get("author"), body.get("introduction"));
            return Result.success(info, "书籍信息已更新");
        } catch (Exception e) {
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /**
     * 重新提取（或首次提取）指定文档的书籍信息.
     * 从OSS下载MD → 解析作者/简介/目录 → 存入book_info表.
     */
    @PostMapping("/{id}/extract-book-info")
    public Result<com.learnthink.core.domain.entity.BookInfo> extractBookInfo(@PathVariable String id) {
        try {
            com.learnthink.core.domain.entity.BookInfo info = bookInfoService.extractFromOss(id);
            return Result.success(info, "书籍信息提取成功");
        } catch (Exception e) {
            return Result.error(500, "提取失败: " + e.getMessage());
        }
    }

    /**
     * 将已解析的章节MD批量提交到RAG知识库构建索引（异步）.
     *
     * <p>从DB查询课程下所有讲义/讲义章节的OSS URL，直接传给RAG服务HTTP下载，
     * 无需OSS SDK。RAG端分块后写入Milvus.</p>
     */
    @PostMapping("/ingest")
    public Result<Map<String, Object>> ingestDocuments(@RequestBody Map<String, Object> body) {
        String courseId = (String) body.get("courseId");
        if (courseId == null || courseId.isBlank()) {
            return Result.error(400, "courseId 不能为空");
        }

        @SuppressWarnings("unchecked")
        List<String> docIds = (List<String>) body.getOrDefault("docIds", List.of());
        boolean fullRebuild = Boolean.TRUE.equals(body.get("fullRebuild"));

        // 从DB查询该课程下所有讲义/讲义章节的OSS URL，传给RAG直接HTTP下载
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getCourseId, courseId)
                .in(KnowledgeDocument::getSourceType, "讲义章节", "讲义");
        List<KnowledgeDocument> docs = knowledgeDocumentMapper.selectList(wrapper);
        List<String> fileUrls = docs.stream()
                .map(KnowledgeDocument::getFilePath)
                .filter(p -> p != null && !p.isBlank())
                .toList();

        try {
            Map<String, Object> result = ragIngestService.ingestDocument(courseId, docIds, fullRebuild, fileUrls);
            return Result.success(result, "已提交知识库构建任务，共 " + fileUrls.size() + " 个文件");
        } catch (Exception e) {
            return Result.error(500, "提交失败: " + e.getMessage());
        }
    }

    /**
     * 查询RAG知识库构建任务进度.
     */
    @GetMapping("/ingest-task/{taskId}")
    public Result<Map<String, Object>> getIngestTask(@PathVariable String taskId) {
        try {
            Map<String, Object> progress = ragIngestService.getTaskProgress(taskId);
            return Result.success(progress);
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 列出RAG知识库构建任务历史.
     */
    @GetMapping("/ingest-tasks")
    public Result<List<Map<String, Object>>> listIngestTasks(
            @RequestParam(required = false) String courseId,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<Map<String, Object>> tasks = ragIngestService.listTasks(courseId, limit);
            return Result.success(tasks);
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }
}
