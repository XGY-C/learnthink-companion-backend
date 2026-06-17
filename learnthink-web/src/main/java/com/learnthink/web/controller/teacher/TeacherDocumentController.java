package com.learnthink.web.controller.teacher;

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

@RestController
@RequestMapping("/teacher/courses/{courseId}/documents")
@RequiredArgsConstructor
public class TeacherDocumentController {

    private final AliOSSUtil ossUtil;
    private final DocumentParseService documentParseService;
    private final RagIngestService ragIngestService;
    private final BookInfoService bookInfoService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    @PostMapping("/upload-pdf")
    public Result<Map<String, Object>> uploadPdf(
            @PathVariable String courseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", defaultValue = "") String title) {
        if (file.isEmpty()) return Result.error(400, "文件不能为空");
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            return Result.error(400, "仅支持 PDF 文件");
        }
        if (file.getSize() > 200 * 1024 * 1024) {
            return Result.error(400, "PDF文件不能超过 200MB");
        }
        try {
            String ossPath = "kb/" + courseId + "/pdf";
            String fileUrl = ossUtil.upload(file, ossPath);
            String docTitle = title.isBlank() ? originalFilename.replaceAll("(?i)\\.pdf$", "") : title;
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

    @PostMapping("/upload-md")
    public Result<Map<String, Object>> uploadMd(
            @PathVariable String courseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", defaultValue = "") String title) {
        if (file.isEmpty()) return Result.error(400, "文件不能为空");
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

    @PostMapping("/parse-submit")
    public Result<Map<String, Object>> submitParse(@PathVariable String courseId, @RequestBody Map<String, String> body) {
        String fileUrl = body.get("fileUrl");
        String title = body.getOrDefault("title", "未命名文档");
        String docId = body.get("id");
        if (fileUrl == null || fileUrl.isBlank()) return Result.error(400, "fileUrl 不能为空");
        try {
            String taskId = documentParseService.submitParse(fileUrl, courseId, title, docId);
            return Result.success(Map.of("taskId", taskId), "已提交解析任务");
        } catch (Exception e) {
            return Result.error(500, "提交解析任务失败: " + e.getMessage());
        }
    }

    @PostMapping("/split-md")
    public Result<Map<String, Object>> splitMd(@PathVariable String courseId, @RequestBody Map<String, String> body) {
        String fileUrl = body.get("fileUrl");
        String docId = body.get("id");
        if (fileUrl == null || fileUrl.isBlank()) return Result.error(400, "fileUrl 不能为空");
        try {
            List<ChapterInfo> chapters = documentParseService.splitMdDocument(fileUrl, courseId, docId);
            return Result.success(Map.of("chapters", chapters, "count", chapters.size()), "拆分完成，共 " + chapters.size() + " 个章节");
        } catch (Exception e) {
            return Result.error(500, "拆分MD失败: " + e.getMessage());
        }
    }

    @GetMapping("/parse-task/{taskId}")
    public Result<ParseTaskProgress> getParseTask(@PathVariable String courseId, @PathVariable String taskId) {
        try {
            return Result.success(documentParseService.getParseProgress(taskId));
        } catch (Exception e) {
            return Result.error(500, "查询解析进度失败: " + e.getMessage());
        }
    }

    @GetMapping
    public Result<List<KnowledgeDocument>> listDocuments(
            @PathVariable String courseId,
            @RequestParam(required = false) String search) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(courseId != null && !courseId.isBlank(), KnowledgeDocument::getCourseId, courseId)
                .orderByDesc(KnowledgeDocument::getCreatedAt);
        return Result.success(knowledgeDocumentMapper.selectList(wrapper));
    }

    @DeleteMapping("/{docId}")
    public Result<Void> deleteDocument(@PathVariable String courseId, @PathVariable String docId) {
        knowledgeDocumentMapper.deleteById(docId);
        return Result.success(null, "已删除");
    }

    @GetMapping("/preview")
    public Result<Map<String, String>> previewChapter(@PathVariable String courseId, @RequestParam String id) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(id);
        if (doc == null) return Result.error(404, "文档不存在");
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) return Result.error(400, "文档无文件路径");
        try (InputStream in = ossUtil.downloadFile(doc.getFilePath())) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String preview = content.length() > 5000 ? content.substring(0, 5000) + "\n\n...（内容过长，仅显示前5000字符）" : content;
            return Result.success(Map.of("content", preview, "title", doc.getTitle()));
        } catch (Exception e) {
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    @GetMapping("/preview-by-url")
    public Result<Map<String, String>> previewByUrl(@PathVariable String courseId, @RequestParam String url) {
        try (InputStream in = ossUtil.downloadFile(url)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String preview = content.length() > 5000 ? content.substring(0, 5000) + "\n\n...（内容过长，仅显示前5000字符）" : content;
            return Result.success(Map.of("content", preview, "title", "完整MD"));
        } catch (Exception e) {
            return Result.error(500, "读取失败: " + e.getMessage());
        }
    }

    @GetMapping("/{docId}/book-info")
    public Result<com.learnthink.core.domain.entity.BookInfo> getBookInfo(@PathVariable String courseId, @PathVariable String docId) {
        return Result.success(bookInfoService.getByDocumentId(docId));
    }

    @PutMapping("/{docId}/book-info")
    public Result<com.learnthink.core.domain.entity.BookInfo> updateBookInfo(
            @PathVariable String courseId, @PathVariable String docId, @RequestBody Map<String, String> body) {
        try {
            return Result.success(bookInfoService.update(docId, body.get("author"), body.get("introduction")), "书籍信息已更新");
        } catch (Exception e) {
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    @PostMapping("/{docId}/extract-book-info")
    public Result<com.learnthink.core.domain.entity.BookInfo> extractBookInfo(@PathVariable String courseId, @PathVariable String docId) {
        try {
            return Result.success(bookInfoService.extractFromOss(docId), "书籍信息提取成功");
        } catch (Exception e) {
            return Result.error(500, "提取失败: " + e.getMessage());
        }
    }

    @PostMapping("/ingest")
    public Result<Map<String, Object>> ingestDocuments(@PathVariable String courseId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> docIds = (List<String>) body.getOrDefault("docIds", List.of());
        boolean fullRebuild = Boolean.TRUE.equals(body.get("fullRebuild"));
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

    @GetMapping("/ingest-task/{taskId}")
    public Result<Map<String, Object>> getIngestTask(@PathVariable String courseId, @PathVariable String taskId) {
        try {
            return Result.success(ragIngestService.getTaskProgress(taskId));
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    @GetMapping("/ingest-tasks")
    public Result<List<Map<String, Object>>> listIngestTasks(
            @PathVariable String courseId,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            return Result.success(ragIngestService.listTasks(courseId, limit));
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }
}
