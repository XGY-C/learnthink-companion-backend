package com.learnthink.web.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG知识库摄入服务 — 调用Python RAG端 /admin/ingest-document 接口.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestService {

    @Qualifier("ragRestTemplate")
    private final RestTemplate ragRestTemplate;

    /**
     * 提交文档到RAG知识库（异步构建Milvus索引）.
     *
     * @param courseId   课程ID
     * @param docIds     文档ID列表（不含.md），为空则同步全部
     * @param fullRebuild 是否全量重建
     * @param fileUrls   文档OSS URL列表，供RAG直接HTTP下载（无需OSS SDK）
     * @return RAG响应: {task_id, status, course_id, message}
     */
    public Map<String, Object> ingestDocument(String courseId, List<String> docIds, boolean fullRebuild, List<String> fileUrls) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("course_id", courseId);
        request.put("doc_ids", docIds != null ? docIds : List.of());
        request.put("full_rebuild", fullRebuild);
        request.put("async_mode", true);
        request.put("oss_prefix", "");
        request.put("file_urls", fileUrls != null ? fileUrls : List.of());

        log.info("Calling RAG ingest: courseId={}, docIds={}, fileUrls={}, fullRebuild={}", courseId, docIds, fileUrls, fullRebuild);

        Map<String, Object> response = ragRestTemplate.postForObject(
                "/admin/ingest-document", request, Map.class);

        log.info("RAG ingest response: {}", response);
        return response;
    }

    /**
     * 查询RAG知识库构建任务进度.
     */
    public Map<String, Object> getTaskProgress(String taskId) {
        return ragRestTemplate.getForObject("/admin/task/{taskId}", Map.class, taskId);
    }

    /**
     * 列出RAG知识库构建任务历史.
     */
    public List<Map<String, Object>> listTasks(String courseId, int limit) {
        StringBuilder url = new StringBuilder("/admin/tasks?limit=").append(limit);
        if (courseId != null && !courseId.isBlank()) {
            url.append("&course_id=").append(courseId);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> response = ragRestTemplate.getForObject(url.toString(), List.class);
            return response != null ? response : List.of();
        } catch (Exception e) {
            log.warn("Failed to list RAG tasks: {}", e.getMessage());
            return List.of();
        }
    }
}
