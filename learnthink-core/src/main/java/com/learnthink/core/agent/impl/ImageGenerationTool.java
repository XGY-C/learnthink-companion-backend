package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class ImageGenerationTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String appId;
    private final String apiKey;
    private final String apiSecret;
    private final String domain;
    private final String baseUrl;

    public ImageGenerationTool(RestTemplate restTemplate, String appId, String apiKey,
                                String apiSecret, String domain, String baseUrl) {
        this.restTemplate = restTemplate;
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.domain = domain;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "generate_image";
    }

    @Override
    public String description() {
        return "Generate images from text descriptions using Spark AI image generation model. " +
               "Returns a base64-encoded PNG image string. Use this when the user asks to create or draw something.";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "Text description of the image to generate (Chinese or English)"
                },
                "width": {
                  "type": "integer",
                  "default": 768,
                  "description": "Image width in pixels (768, 1024, 576)"
                },
                "height": {
                  "type": "integer",
                  "default": 768,
                  "description": "Image height in pixels (768, 1024, 1024)"
                },
                "negative_prompt": {
                  "type": "string",
                  "description": "Elements to avoid in the generated image"
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
                return "{\"error\":\"PROMPT_REQUIRED\",\"message\":\"Image prompt cannot be empty\"}";
            }

            int width = args.containsKey("width") ? ((Number) args.get("width")).intValue() : 768;
            int height = args.containsKey("height") ? ((Number) args.get("height")).intValue() : 768;
            String negativePrompt = (String) args.getOrDefault("negative_prompt", null);

            String authUrl = buildAuthUrl();
            log.info("ImageGenerationTool generating: prompt='{}', size={}x{}", prompt, width, height);

            Map<String, Object> request = buildRequest(prompt, width, height, negativePrompt);
            String response = restTemplate.postForObject(authUrl, request, String.class);

            if (response == null) {
                return "{\"error\":\"API_ERROR\",\"message\":\"Empty response from Spark API\"}";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> respMap = mapper.readValue(response, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> header = (Map<String, Object>) respMap.get("header");
            int code = (int) header.getOrDefault("code", -1);

            if (code != 0) {
                String msg = (String) header.getOrDefault("message", "Unknown error");
                log.warn("Spark API error: code={}, message={}", code, msg);
                return "{\"error\":\"API_ERROR\",\"code\":" + code + ",\"message\":\"" + msg + "\"}";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) respMap.get("payload");
            @SuppressWarnings("unchecked")
            Map<String, Object> choices = (Map<String, Object>) payload.get("choices");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> texts = (List<Map<String, Object>>) choices.get("text");
            if (texts == null || texts.isEmpty()) {
                return "{\"error\":\"NO_RESULT\",\"message\":\"No image generated\"}";
            }

            String base64Image = (String) texts.get(0).get("content");
            Map<String, Object> result = Map.of(
                "image_base64", base64Image,
                "format", "png",
                "prompt", prompt,
                "width", width,
                "height", height
            );
            return mapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("ImageGenerationTool failed", e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String buildAuthUrl() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT"));
        String date = now.format(DateTimeFormatter.RFC_1123_DATE_TIME);

        java.net.URL url = new java.net.URL(baseUrl);
        String host = url.getHost();
        String path = url.getPath();

        String tmp = "host: " + host + "\n" +
                     "date: " + date + "\n" +
                     "POST " + path + " HTTP/1.1";

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
            apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] signatureBytes = mac.doFinal(tmp.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signatureBytes);

        String authorizationOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", signature=\"" + signature + "\"";
        String authorization = Base64.getEncoder()
            .encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

        String query = "authorization=" + URLEncoder.encode(authorization, "UTF-8") +
                       "&date=" + URLEncoder.encode(date, "UTF-8") +
                       "&host=" + URLEncoder.encode(host, "UTF-8");

        return baseUrl + "?" + query;
    }

    private Map<String, Object> buildRequest(String prompt, int width, int height,
                                              String negativePrompt) {
        Map<String, Object> header = Map.of("app_id", appId);

        Map<String, Object> chatParams = new java.util.LinkedHashMap<>();
        chatParams.put("domain", domain);
        chatParams.put("width", width);
        chatParams.put("height", height);
        chatParams.put("seed", 42);
        chatParams.put("num_inference_steps", 20);
        chatParams.put("guidance_scale", 5.0);
        chatParams.put("scheduler", "Euler");

        Map<String, Object> parameter = Map.of("chat", chatParams);

        Map<String, Object> textMessage = Map.of("role", "user", "content", prompt);
        Map<String, Object> message = Map.of("text", List.of(textMessage));

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("message", message);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            payload.put("negative_prompts", Map.of("text", negativePrompt));
        }

        return Map.of(
            "header", header,
            "parameter", parameter,
            "payload", payload
        );
    }

    public String generate(String prompt, int width, int height, String negativePrompt) {
        try {
            String args = mapper.writeValueAsString(Map.of(
                "prompt", prompt,
                "width", width,
                "height", height,
                "negative_prompt", negativePrompt != null ? negativePrompt : ""
            ));
            return execute(args);
        } catch (Exception e) {
            log.error("ImageGenerationTool.generate failed", e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
