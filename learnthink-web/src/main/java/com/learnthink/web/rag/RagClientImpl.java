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
        body.put("topic", topic != null ? topic : "");
        body.put("min_relevance", minRelevance);
        body.put("min_sources", minSources);
        body.put("query_mode", "raw");
        body.put("search_mode", "hybrid");
        body.put("sparse_weight", 0.3);

        try {
            // 设置请求头，确保 Content-Type 为 application/json
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<java.util.HashMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            log.info("RAG request - URL: /internal/rag/retrieve, Body: {}", body);
            log.info("RAG request - Headers: {}", headers);

            // 使用 exchange 方法代替 postForObject，以获得更好的控制
            org.springframework.http.ResponseEntity<Map> response = restTemplate.exchange(
                "/internal/rag/retrieve",
                org.springframework.http.HttpMethod.POST,
                requestEntity,
                Map.class
            );

            log.info("RAG response status: {}", response.getStatusCode());
            log.info("RAG response headers: {}", response.getHeaders());

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = response.getBody();

            if (resp == null) {
                log.warn("RAG service returned null response for course={}", courseId);
                return null;
            }

            log.info("RAG response body keys: {}", resp.keySet());
            log.info("RAG response full body: {}", resp);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) resp.getOrDefault("sources", Collections.emptyList());

            log.info("RAG sources count: {}", sources.size());
            if (!sources.isEmpty()) {
                log.info("RAG first source: {}", sources.get(0));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) resp.get("stats");
            String mode = stats != null ? String.valueOf(stats.getOrDefault("query_mode", "hybrid")) : "hybrid";

            log.info("RAG query mode: {}, stats: {}", mode, stats);

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
