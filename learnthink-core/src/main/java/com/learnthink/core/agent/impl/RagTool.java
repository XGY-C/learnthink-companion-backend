package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库检索工具（共享 AgentTool）
 * <p>任何注册了该工具的 Agent 都可以独立搜索知识库。
 * 权限模型（在 Agent 注册时强制）：
 * <ul>
 *   <li>EvidenceRetriever — READ_WRITE（主检索 + 补充检索）</li>
 *   <li>ContentReviewer — READ（事实核查：针对特定声明的定向检索）</li>
 *   <li>TutorAgent — READ（问答：检索相关材料以支持回答）</li>
 *   <li>ResourceGenerator — READ（可选：生成过程中自行检索具体细节）</li>
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
            int k = args.containsKey("k") ? ((Number) args.get("k")).intValue() : 200;
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

    /** 便捷方法：为 Java 调用者提供的类型化检索 */
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
