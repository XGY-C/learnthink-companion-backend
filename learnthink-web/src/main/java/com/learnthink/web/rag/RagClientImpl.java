package com.learnthink.web.rag;

import com.learnthink.core.agent.impl.EvidenceRetriever.RagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class RagClientImpl implements RagClient {

    private static final Logger log = LoggerFactory.getLogger(RagClientImpl.class);
    private final RestTemplate restTemplate;

    public RagClientImpl(RestTemplate ragRestTemplate) {
        this.restTemplate = ragRestTemplate;
        log.info("RagClientImpl initialized with RestTemplate: {}", restTemplate.getClass().getName());
    }

    @Override
    public RagResponse retrieve(String courseId, String query, String topic,
                                int k, double minRelevance, int minSources) {
        // 使用 HashMap 而不是 Map.of()，确保可序列化
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("course_id", courseId);
        body.put("query", query);
        body.put("k", k);
        // 注意：topic 参数会导致 RAG 服务端额外过滤，暂时禁用（与 curl 测试保持一致）
        body.put("topic", "");
        // 限制 min_relevance 最高为 0.25，避免过滤掉有效结果
        body.put("min_relevance", Math.min(minRelevance, 0.25));
        // 确保 min_sources 至少为 1，避免无结果时直接返回空数组
        body.put("min_sources", Math.max(minSources, 1));
        body.put("query_mode", "raw");
        body.put("search_mode", "hybrid");
        body.put("sparse_weight", 0.3);

        try {
            // 设置请求头，确保 Content-Type 为 application/json
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<java.util.HashMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            log.info("=== RAG REQUEST DETAIL ===");
            log.info("URL: /internal/rag/retrieve");
            log.info("Body JSON: {}", body);
            log.info("Headers: {}", headers);

            // 使用 exchange 方法代替 postForObject，以获得更好的控制
            org.springframework.http.ResponseEntity<Map> response = restTemplate.exchange(
                "/internal/rag/retrieve",
                org.springframework.http.HttpMethod.POST,
                requestEntity,
                Map.class
            );

            log.info("=== RAG RESPONSE DETAIL ===");
            log.info("Status: {}", response.getStatusCode());
            log.info("Headers: {}", response.getHeaders());

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = response.getBody();

            if (resp == null) {
                log.warn("RAG service returned null response for course={}", courseId);
                return null;
            }

            log.info("Response keys: {}", resp.keySet());
            log.info("Full response body: {}", resp);
            
            // 详细记录 sources 信息
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) resp.getOrDefault("sources", Collections.emptyList());
            log.info("Sources count: {}, isEmpty: {}", sources.size(), sources.isEmpty());
            
            if (!sources.isEmpty()) {
                log.info("First source detail: {}", sources.get(0));
                log.info("All source doc_ids: {}", sources.stream().map(s -> s.get("doc_id")).toList());
            } else {
                log.warn("⚠️ RAG returned EMPTY sources! Possible causes:");
                log.warn("  1. Course '{}' has no indexed documents", courseId);
                log.warn("  2. Query '{}' too restrictive", query);
                log.warn("  3. Topic filter was applied (now disabled)", topic);
                log.warn("  4. RAG service internal filtering");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) resp.get("stats");
            String mode = stats != null ? String.valueOf(stats.getOrDefault("query_mode", "hybrid")) : "hybrid";
            log.info("Query mode: {}, Full stats: {}", mode, stats);

            List<SourceRef> refs = sources.stream()
                .map(s -> new SourceRef(
                    str(s, "doc_id"),
                    str(s, "book_title"),
                    str(s, "book_type"),
                    intOrNull(s, "chapter_index"),
                    str(s, "chapter_title"),
                    str(s, "source_type"),
                    str(s, "chunk_id"),
                    str(s, "excerpt"),
                    str(s, "locator"),
                    str(s, "heading_path"),
                    dbl(s, "relevance")))
                .toList();

            return new RagResponse(refs, mode);

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 503) {
                log.warn("RAG knowledge base not ready for course={}: {}", courseId, e.getMessage());
            } else {
                log.error("RAG service error ({}): {}", e.getStatusCode().value(), e.getMessage());
                log.error("RAG service error response body: {}", e.getResponseBodyAsString());
            }
            return null;
        } catch (Exception e) {
            log.error("RAG service unreachable: {}", e.getMessage());
            return null;
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

    private static Integer intOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return null;
    }
}
