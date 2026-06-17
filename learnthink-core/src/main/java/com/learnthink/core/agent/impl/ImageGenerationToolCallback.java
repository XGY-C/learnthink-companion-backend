package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

public class ImageGenerationToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImageGenerationTool imageTool;

    public ImageGenerationToolCallback(ImageGenerationTool imageTool) {
        this.imageTool = imageTool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("generate_image")
            .description("根据文字描述生成图片。当用户要求画图、生成图片、插图、示意图时调用此工具。" +
                         "返回 base64 编码的 PNG 图片。支持中文描述。")
            .inputSchema("""
                {
                  "type": "object",
                  "properties": {
                    "prompt": {
                      "type": "string",
                      "description": "图片描述文字（中文或英文均可）"
                    },
                    "width": {
                      "type": "integer",
                      "description": "图片宽度像素（768, 1024, 576）",
                      "default": 768
                    },
                    "height": {
                      "type": "integer",
                      "description": "图片高度像素（768, 1024, 1024）",
                      "default": 768
                    },
                    "negative_prompt": {
                      "type": "string",
                      "description": "图片中要避免的元素"
                    }
                  },
                  "required": ["prompt"],
                  "additionalProperties": false
                }""")
            .build();
    }

    @Override
    public String call(String functionInput) {
        log.info("ImageGenerationToolCallback invoked: input={}", functionInput);
        try {
            Map<String, Object> args = MAPPER.readValue(functionInput,
                new TypeReference<Map<String, Object>>() {});
            String prompt = (String) args.getOrDefault("prompt", "");
            int width = args.containsKey("width") ? ((Number) args.get("width")).intValue() : 768;
            int height = args.containsKey("height") ? ((Number) args.get("height")).intValue() : 768;
            String negativePrompt = (String) args.getOrDefault("negative_prompt", null);

            return imageTool.generate(prompt, width, height, negativePrompt);
        } catch (Exception e) {
            log.error("ImageGenerationToolCallback failed", e);
            return "{\"error\":\"CALLBACK_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
