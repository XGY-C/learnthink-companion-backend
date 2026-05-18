package com.learnthink.web.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.domain.entity.KnowledgeDocument;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import com.learnthink.core.service.admin.ChapterInfo;
import com.learnthink.core.service.admin.DocumentParseService;
import com.learnthink.web.rag.RagIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fileUrl", fileUrl);
            data.put("fileName", originalFilename);
            data.put("fileSize", file.getSize());
            data.put("courseId", courseId);
            data.put("title", title.isBlank() ? originalFilename.replaceAll("(?i)\\.pdf$", "") : title);

            return Result.success(data, "上传成功");
        } catch (Exception e) {
            return Result.error(500, "上传失败: " + e.getMessage());
        }
    }

    /**
     * 解析PDF: Mineru转MD → 按章拆分 → 章节MD上传OSS → 入库.
     */
    @PostMapping("/parse-pdf")
    public Result<List<ChapterInfo>> parsePdf(@RequestBody Map<String, String> body) {
        String fileUrl = body.get("fileUrl");
        String courseId = body.get("courseId");
        String title = body.getOrDefault("title", "未命名文档");

        if (fileUrl == null || fileUrl.isBlank()) {
            return Result.error(400, "fileUrl 不能为空");
        }
        if (courseId == null || courseId.isBlank()) {
            return Result.error(400, "courseId 不能为空");
        }

        try {
            List<ChapterInfo> chapters = documentParseService.parsePdf(fileUrl, courseId, title);
            return Result.success(chapters, "解析完成，共 " + chapters.size() + " 个章节");
        } catch (Exception e) {
            return Result.error(500, "解析失败: " + e.getMessage());
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
     * 将已解析的章节MD批量提交到RAG知识库构建索引（异步）.
     *
     * <p>RAG端会从OSS下载 kb/{courseId}/processed/*.md 文件，分块后写入Milvus.
     * 返回taskId，前端可通过 /admin/documents/ingest-task/{taskId} 轮询进度.</p>
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

        try {
            Map<String, Object> result = ragIngestService.ingestDocument(courseId, docIds, fullRebuild);
            return Result.success(result, "已提交知识库构建任务");
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
}
