package com.learnthink.web.controller.teacher;

import com.learnthink.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/teacher/courses/{courseId}/retrieval")
@RequiredArgsConstructor
public class TeacherRetrievalController {

    private final RestTemplate ragRestTemplate;

    @PostMapping("/test")
    public Result<List<Map<String, Object>>> retrieve(
            @PathVariable String courseId,
            @RequestBody Map<String, Object> body) {
        String query = (String) body.getOrDefault("query", "");
        String mode = (String) body.getOrDefault("mode", "hybrid");
        int k = body.get("k") instanceof Number n ? n.intValue() : 10;
        double sparseWeight = body.get("sparseWeight") instanceof Number n2 ? n2.doubleValue() : 0.3;

        if (query.isBlank()) return Result.error(400, "query 不能为空");

        Map<String, Object> ragRequest = new LinkedHashMap<>();
        ragRequest.put("course_id", courseId);
        ragRequest.put("query", query);
        ragRequest.put("k", k);
        ragRequest.put("topic", "");
        ragRequest.put("min_relevance", 0.0);
        ragRequest.put("min_sources", 1);
        ragRequest.put("query_mode", "raw");
        ragRequest.put("search_mode", mode);
        ragRequest.put("sparse_weight", sparseWeight);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(ragRequest, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> ragResponse = ragRestTemplate.postForObject(
                    "/internal/rag/retrieve", entity, Map.class);

            if (ragResponse == null) return Result.success(List.of(), "RAG 服务无响应");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) ragResponse.getOrDefault("sources", List.of());

            List<Map<String, Object>> results = sources.stream().map(s -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkId", str(s, "chunk_id"));
                item.put("content", str(s, "excerpt"));
                item.put("score", dbl(s, "relevance"));
                item.put("sourceDoc", str(s, "book_title"));
                item.put("courseId", str(s, "doc_id"));
                item.put("keywords", List.of());
                return item;
            }).collect(Collectors.toList());

            return Result.success(results, "检索完成，共 " + results.size() + " 条结果");
        } catch (Exception e) {
            log.error("RAG retrieval test failed: {}", e.getMessage());
            return Result.error(500, "检索失败: " + e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }

    private static double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
