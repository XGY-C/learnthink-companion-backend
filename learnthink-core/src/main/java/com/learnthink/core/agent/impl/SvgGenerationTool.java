package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

public class SvgGenerationTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SvgGenerationTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ChatClient chatClient;

    public SvgGenerationTool(ChatClient.Builder builder) {
        this.chatClient = builder
            .defaultSystem("You are an SVG code generator. Given a user description, " +
                "output ONLY valid SVG code wrapped in ```svg...``` block. " +
                "Use proper SVG attributes (viewBox, xmlns, responsive design). " +
                "Do NOT include any explanation before or after the SVG code. " +
                "Support gradients, shapes, text, paths, and animations. " +
                "Ensure the SVG renders correctly as a standalone file. " +
                "Prefer modern, clean design with good use of colors and spacing.")
            .build();
    }

    @Override
    public String name() {
        return "generate_svg";
    }

    @Override
    public String description() {
        return "Generate SVG (Scalable Vector Graphics) code from text descriptions. " +
               "Returns SVG code wrapped in a markdown code block. " +
               "Use this when the user wants a diagram, icon, illustration, chart, or any vector graphic.";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "Description of the SVG to generate (e.g., 'a red circle with blue border')"
                },
                "style": {
                  "type": "string",
                  "description": "Visual style (flat, minimal, colorful, dark, gradient, line-art)"
                }
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = mapper.readValue(jsonArgs, Map.class);
            String prompt = (String) args.get("prompt");
            if (prompt == null || prompt.isBlank()) {
                return "{\"error\":\"PROMPT_REQUIRED\",\"message\":\"SVG prompt cannot be empty\"}";
            }

            String style = (String) args.getOrDefault("style", "");
            String userRequest = style != null && !style.isBlank()
                ? "Create an SVG: " + prompt + ". Style: " + style + "."
                : "Create an SVG: " + prompt + ".";

            log.info("SvgGenerationTool generating: prompt='{}', style='{}'", prompt, style);

            String response = chatClient.prompt()
                .user(userRequest)
                .call()
                .content();

            if (response == null || response.isBlank()) {
                return "{\"error\":\"NO_RESPONSE\",\"message\":\"Empty response from AI model\"}";
            }

            Map<String, Object> result = Map.of(
                "svg_code", response,
                "prompt", prompt,
                "style", style != null ? style : ""
            );
            return mapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("SvgGenerationTool failed", e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    public String generate(String prompt, String style) {
        try {
            String args = mapper.writeValueAsString(Map.of(
                "prompt", prompt,
                "style", style != null ? style : ""
            ));
            return execute(args);
        } catch (Exception e) {
            log.error("SvgGenerationTool.generate failed", e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
