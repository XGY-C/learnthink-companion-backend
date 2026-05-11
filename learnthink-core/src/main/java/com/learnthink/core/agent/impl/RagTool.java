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
 *   <li>RetrieverAgent — READ_WRITE (primary retrieval + supplementary retrieval)</li>
 *   <li>ReviewerAgent — READ (fact-checking: targeted retrieval for specific claims)</li>
 *   <li>TutorAgent — READ (Q&A: retrieve relevant materials to support answers)</li>
 *   <li>GeneratorAgent — READ (optional: self-retrieve specific details during generation)</li>
 * </ul>
 */
public class RagTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(RagTool.class);

    private final RetrieverAgent.RagClient ragClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagTool(RetrieverAgent.RagClient ragClient) {
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
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(jsonArgs, Map.class);
            String courseId = (String) args.get("course_id");
            String query = (String) args.get("query");
            int k = args.containsKey("k") ? ((Number) args.get("k")).intValue() : 8;
            String topic = (String) args.getOrDefault("topic", null);

            RetrieverAgent.RagClient.RagResponse resp = ragClient.retrieve(courseId, query, topic, k, 0.4, 1);
            if (resp == null) {
                return mapper.writeValueAsString(Map.of("sources", List.of(), "error", "KB_NOT_READY"));
            }
            return mapper.writeValueAsString(resp);

        } catch (JsonProcessingException e) {
            log.error("RagTool: failed to parse args", e);
            return "{\"sources\":[],\"error\":\"INVALID_ARGS\"}";
        }
    }

    /** Convenience: typed retrieval for Java callers */
    public RetrieverAgent.RagClient.RagResponse retrieve(String courseId, String query, String topic, int k) {
        return ragClient.retrieve(courseId, query, topic, k, 0.4, 1);
    }
}
