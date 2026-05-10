package com.learnthink.web.rag;

import com.learnthink.core.agent.impl.RetrieverAgent.RagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    }

    @Override
    public RagResponse retrieve(String courseId, String query, String topic,
                                int k, double minRelevance, int minSources) {
        Map<String, Object> body = Map.of(
            "course_id", courseId,
            "query", query,
            "k", k,
            "topic", topic != null ? topic : "",
            "min_relevance", minRelevance,
            "min_sources", minSources,
            "query_mode", "raw",
            "search_mode", "hybrid",
            "sparse_weight", 0.3
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(
                "/internal/rag/retrieve", body, Map.class);

            if (resp == null) {
                log.warn("RAG service returned null response for course={}", courseId);
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) resp.getOrDefault("sources", Collections.emptyList());

            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) resp.get("stats");
            String mode = stats != null ? String.valueOf(stats.getOrDefault("query_mode", "hybrid")) : "hybrid";

            List<SourceRef> refs = sources.stream()
                .map(s -> new SourceRef(
                    str(s, "doc_id"),
                    str(s, "doc_title"),
                    str(s, "chunk_id"),
                    str(s, "excerpt"),
                    str(s, "locator"),
                    dbl(s, "relevance")))
                .toList();

            return new RagResponse(refs, mode);

        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 503) {
                log.warn("RAG knowledge base not ready for course={}: {}", courseId, e.getMessage());
            } else {
                log.error("RAG service error ({}): {}", e.getStatusCode().value(), e.getMessage());
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
}
