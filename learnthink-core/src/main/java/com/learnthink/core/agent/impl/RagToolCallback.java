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
 * Spring AI {@link ToolCallback} 包装器——用于 RAG 知识库检索
 *
 * <p>按次注册（不作为 Spring Bean），因此仅出现在对话 LLM 的工具集中——
 * 不会出现在画像充分性评估、意图检测或其他内部 LLM 调用中。
 *
 * <p>当对话 LLM 在流式处理中决定调用此工具时，
 * {@code onCall} 和 {@code onResult} 钩子会在工具执行期间同步触发。
 * 调用方使用这些钩子将 SSE 思考事件实时发射到流式响应中。
 */
public class RagToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RagToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagTool ragTool;
    private final String courseId;
    private final List<Runnable> onCall;
    private final Consumer<String> onResult;

    /**
     * @param ragTool  共享的 RAG 检索工具
     * @param courseId 课程 UUID，构造时注入
     * @param onCall   检索前执行（发射 RETRIEVE 思考事件），可为 null
     * @param onResult 检索后执行，传入 JSON 结果字符串（发射 RAG 思考事件），可为 null
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
