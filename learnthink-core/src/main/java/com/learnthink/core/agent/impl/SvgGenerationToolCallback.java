package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

public class SvgGenerationToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SvgGenerationToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SvgGenerationTool svgTool;

    public SvgGenerationToolCallback(SvgGenerationTool svgTool) {
        this.svgTool = svgTool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("generate_svg")
            .description("根据文字描述生成 SVG 矢量图。当用户要求画图、生成图表、图标、示意图、流程图、插画等" +
                         "矢量图形时调用此工具。返回包含 SVG 代码的 Markdown 代码块。支持中文描述。")
            .inputSchema("""
                {
                  "type": "object",
                  "properties": {
                    "prompt": {
                      "type": "string",
                      "description": "图形描述文字（如'一个带蓝色边框的红色圆形'）"
                    },
                    "style": {
                      "type": "string",
                      "description": "视觉风格（flat扁平, minimal极简, colorful多彩, dark深色, gradient渐变, line-art线条）"
                    }
                  },
                  "required": ["prompt"],
                  "additionalProperties": false
                }""")
            .build();
    }

    @Override
    public String call(String functionInput) {
        log.info("SvgGenerationToolCallback invoked: input={}", functionInput);
        try {
            Map<String, Object> args = MAPPER.readValue(functionInput,
                new TypeReference<Map<String, Object>>() {});
            String prompt = (String) args.getOrDefault("prompt", "");
            String style = (String) args.getOrDefault("style", null);

            return svgTool.generate(prompt, style);
        } catch (Exception e) {
            log.error("SvgGenerationToolCallback failed", e);
            return "{\"error\":\"CALLBACK_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
