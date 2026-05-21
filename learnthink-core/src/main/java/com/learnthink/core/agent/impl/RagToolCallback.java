package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Spring AI {@link ToolCallback} wrapper for RAG knowledge base retrieval.
 *
 * <p>Registered per-call (not as a Spring bean) so it only appears in the
 * conversation LLM's tool set — not in sufficiency evaluation, intent
 * detection, or other internal LLM calls.
 *
 * <p>When the conversation LLM decides to call this tool during streaming,
 * the {@code onCall} and {@code onResult} hooks fire synchronously during
 * tool execution. Callers use these hooks to emit SSE thought events into
 * the streaming response in real time.
 */
public class RagToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RagToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagTool ragTool;
    private final String courseId;
    private final List<Runnable> onCall;
    private final Consumer<String> onResult;

    /**
     * @param ragTool  shared RAG retrieval tool
     * @param courseId course UUID, injected at construction time
     * @param onCall   runs before retrieval (emit RETRIEVE thought), may be null
     * @param onResult runs after retrieval with JSON result string (emit RAG thought), may be null
     */
    public RagToolCallback(RagTool ragTool, String courseId,
                            List<Runnable> onCall,
                            Consumer<String> onResult) {
        this.ragTool = ragTool;
        this.courseId = courseId;
        this.onCall = onCall;
        this.onResult = onResult;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("rag_retrieve")
            .description("检索课程知识库获取知识点相关资料。" +
                         "当需要回答学生关于课程概念、原理、公式、代码、定义等知识性问题时，" +
                         "调用此工具获取准确信息。返回资料含原文摘录、来源和相关性评分。")
            .inputSchema("""
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "检索关键词或自然语言问题（中文术语优先，可补充英文关键词）"
                    },
                    "k": {
                      "type": "integer",
                      "description": "返回资料数量（1-10）",
                      "default": 5
                    }
                  },
                  "required": ["query", "k"],
                  "additionalProperties": false
                }""")
            .build();
    }

    @Override
    public String call(String functionInput) {
        log.info("RagToolCallback invoked: input={}", functionInput);
        try {
            Map<String, Object> args = MAPPER.readValue(functionInput,
                new TypeReference<Map<String, Object>>() {});
            String query = (String) args.getOrDefault("query", "");
            int k = args.containsKey("k") ? ((Number) args.get("k")).intValue() : 5;

            if (onCall != null) {
                onCall.forEach(Runnable::run);
            }

            var resp = ragTool.retrieve(courseId, query, null, k);
            String result = MAPPER.writeValueAsString(resp);

            if (onResult != null) {
                onResult.accept(result);
            }

            return result;

        } catch (Exception e) {
            log.error("RagToolCallback failed: {}", e.getMessage());
            return "{\"sources\":[],\"error\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
