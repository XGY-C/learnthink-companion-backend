package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.framework.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * RAG knowledge base retrieval as a shared AgentTool.
 *
 * <p>Any Agent with this tool in its registry can independently search the knowledge base.
 * Permission model (enforced at Agent registration time):
 * <ul>
 *   <li>EvidenceRetriever — READ_WRITE (primary retrieval + supplementary retrieval)</li>
 *   <li>ContentReviewer — READ (fact-checking: targeted retrieval for specific claims)</li>
 *   <li>TutorAgent — READ (Q&A: retrieve relevant materials to support answers)</li>
 *   <li>ResourceGenerator — READ (optional: self-retrieve specific details during generation)</li>
 * </ul>
 */
public class RagTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(RagTool.class);

    private final EvidenceRetriever.RagClient ragClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagTool(EvidenceRetriever.RagClient ragClient) {
        this.ragClient = ragClient;
    }

    @Override
    public String name() {
        return "rag_retrieve";
    }

    @Override
    public String description() {
        return "Search the course knowledge base for relevant learning materials. " +
               "Returns document chunks with excerpts, relevance scores, and source citations.";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "course_id": {"type": "string", "description": "Course UUID"},
                "query": {"type": "string", "description": "Search query in natural language"},
                "k": {"type": "integer", "default": 8, "description": "Number of results (1-50)"},
                "topic": {"type": "string", "description": "Optional topic filter"}
              },
              "required": ["course_id", "query"]
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            log.info("RagTool.execute called with args: {}", jsonArgs);
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(jsonArgs, Map.class);
            String courseId = (String) args.get("course_id");
            String query = (String) args.get("query");
            int k = args.containsKey("k") ? ((Number) args.get("k")).intValue() : 8;
            String topic = (String) args.getOrDefault("topic", null);

            log.info("RagTool executing retrieve - courseId: {}, query: '{}', k: {}, topic: {}", 
                courseId, query, k, topic);

            EvidenceRetriever.RagClient.RagResponse resp = ragClient.retrieve(courseId, query, topic, k, 0.4, 1);
            if (resp == null) {
                log.warn("RagTool returned null response");
                return mapper.writeValueAsString(Map.of("sources", List.of(), "error", "KB_NOT_READY"));
            }
            log.info("RagTool retrieved {} sources", resp.sources() != null ? resp.sources().size() : 0);
            return mapper.writeValueAsString(resp);

        } catch (JsonProcessingException e) {
            log.error("RagTool: failed to parse args", e);
            return "{\"sources\":[],\"error\":\"INVALID_ARGS\"}";
        }
    }

    /** Convenience: typed retrieval for Java callers */
    public EvidenceRetriever.RagClient.RagResponse retrieve(String courseId, String query, String topic, int k) {
        log.info("RagTool.retrieve called - courseId: {}, query: '{}', k: {}, topic: {}", 
            courseId, query, k, topic);
        EvidenceRetriever.RagClient.RagResponse resp = ragClient.retrieve(courseId, query, topic, k, 0.4, 1);
        if (resp != null && resp.sources() != null) {
            log.info("RagTool.retrieve returned {} sources", resp.sources().size());
        } else {
            log.warn("RagTool.retrieve returned null or empty sources");
        }
        return resp;
    }
}
