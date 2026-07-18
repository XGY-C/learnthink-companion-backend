package com.learnthink.core.agent.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    /** 图片生成提供者："spark" 或 "qwen" */
    private final String provider;
    // ── 讯飞 Spark 配置 ──
    private final String appId;
    private final String apiKey;
    private final String apiSecret;
    private final String domain;
    private final String baseUrl;
    // ── 千问 Qwen-Image 配置 ──
    private final String qwenApiKey;
    private final String qwenModel;
    private final String qwenBaseUrl;
    private final boolean qwenPromptExtend;
    private final boolean qwenWatermark;

    public ImageGenerationTool(RestTemplate restTemplate, String provider,
                                String appId, String apiKey, String apiSecret,
                                String domain, String baseUrl,
                                String qwenApiKey, String qwenModel, String qwenBaseUrl,
                                boolean qwenPromptExtend, boolean qwenWatermark) {
        this.restTemplate = restTemplate;
        this.provider = provider != null ? provider : "spark";
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.domain = domain;
        this.baseUrl = baseUrl;
        this.qwenApiKey = qwenApiKey;
        this.qwenModel = qwenModel;
        this.qwenBaseUrl = qwenBaseUrl;
        this.qwenPromptExtend = qwenPromptExtend;
        this.qwenWatermark = qwenWatermark;
    }

    @Override
    public String name() {
        return "generate_image";
    }

    @Override
    public String description() {
        return "Generate images from text descriptions using AI image generation model (" + provider + "). " +
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

            log.info("ImageGenerationTool generating: provider={}, prompt='{}', size={}x{}",
                provider, prompt, width, height);

            return "qwen".equalsIgnoreCase(provider)
                ? executeQwen(prompt, width, height, negativePrompt)
                : executeSpark(prompt, width, height, negativePrompt);

        } catch (Exception e) {
            log.error("ImageGenerationTool failed", e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ================================================================
    // 讯飞 Spark 文生图
    // ================================================================

    private String executeSpark(String prompt, int width, int height, String negativePrompt) {
        try {
            String authUrl = buildAuthUrl();
            log.info("ImageGenerationTool[spark] auth diagnostic: hasQuery={}, apiKey.len={}, apiSecret.len={}, appId.len={}",
                authUrl.contains("?"),
                apiKey != null ? apiKey.length() : -1,
                apiSecret != null ? apiSecret.length() : -1,
                appId != null ? appId.length() : -1);

            Map<String, Object> request = buildSparkRequest(prompt, width, height, negativePrompt);
            String response;
            try {
                URI authUri = new URI(authUrl);
                log.info("ImageGenerationTool[spark] request URI: scheme={}, host={}, path={}, query.len={}",
                    authUri.getScheme(), authUri.getHost(), authUri.getPath(),
                    authUri.getRawQuery() != null ? authUri.getRawQuery().length() : -1);
                HttpHeaders reqHeaders = new HttpHeaders();
                reqHeaders.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> reqEntity = new HttpEntity<>(request, reqHeaders);
                response = restTemplate.postForObject(authUri, reqEntity, String.class);
            } catch (Exception e) {
                log.error("ImageGenerationTool[spark] HTTP request failed: {} | baseUrl={} | appId={}",
                    e.getMessage(), baseUrl,
                    appId != null ? appId.substring(0, Math.min(appId.length(), 8)) + "***" : "(null)");
                return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                    e.getMessage().replace("\"", "'") + "\"}";
            }

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
            log.error("ImageGenerationTool[spark] failed", e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ================================================================
    // 千问 Qwen-Image 文生图（DashScope API）
    // ================================================================

    private String executeQwen(String prompt, int width, int height, String negativePrompt) {
        try {
            if (qwenApiKey == null || qwenApiKey.isBlank()) {
                return "{\"error\":\"CONFIG_ERROR\",\"message\":\"DashScope API key not configured (DASHSCOPE_API_KEY)\"}";
            }

            // 构建 DashScope 请求体
            Map<String, Object> textContent = Map.of("text", prompt);
            Map<String, Object> userMessage = Map.of("role", "user", "content", List.of(textContent));
            Map<String, Object> input = Map.of("messages", List.of(userMessage));

            Map<String, Object> parameters = new java.util.LinkedHashMap<>();
            parameters.put("size", width + "*" + height);
            parameters.put("n", 1);
            parameters.put("prompt_extend", qwenPromptExtend);
            parameters.put("watermark", qwenWatermark);
            if (negativePrompt != null && !negativePrompt.isBlank()) {
                parameters.put("negative_prompt", negativePrompt);
            }

            Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("model", qwenModel);
            requestBody.put("input", input);
            requestBody.put("parameters", parameters);

            // 设置请求头（Bearer Token 认证）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(qwenApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("ImageGenerationTool[qwen] calling DashScope: model={}, size={}*{}", qwenModel, width, height);

            String response;
            try {
                response = restTemplate.postForObject(qwenBaseUrl, entity, String.class);
            } catch (Exception e) {
                log.error("ImageGenerationTool[qwen] HTTP request failed: {}", e.getMessage());
                return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                    e.getMessage().replace("\"", "'") + "\"}";
            }

            if (response == null) {
                return "{\"error\":\"API_ERROR\",\"message\":\"Empty response from DashScope API\"}";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> respMap = mapper.readValue(response, Map.class);

            // DashScope 错误响应: {"code":"...","message":"...","request_id":"..."}
            if (respMap.containsKey("code") && respMap.get("code") != null) {
                String code = String.valueOf(respMap.get("code"));
                String msg = (String) respMap.getOrDefault("message", "Unknown error");
                log.warn("DashScope API error: code={}, message={}", code, msg);
                return "{\"error\":\"API_ERROR\",\"code\":\"" + code + "\",\"message\":\"" + msg + "\"}";
            }

            // 解析输出: output.choices[0].message.content[0].image
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) respMap.get("output");
            if (output == null) {
                return "{\"error\":\"NO_RESULT\",\"message\":\"No output in DashScope response\"}";
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
            if (choices == null || choices.isEmpty()) {
                return "{\"error\":\"NO_RESULT\",\"message\":\"No image generated\"}";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contentList = (List<Map<String, Object>>) message.get("content");

            String imageUrl = null;
            for (Map<String, Object> item : contentList) {
                if (item.containsKey("image")) {
                    imageUrl = (String) item.get("image");
                    break;
                }
            }

            if (imageUrl == null || imageUrl.isBlank()) {
                return "{\"error\":\"NO_RESULT\",\"message\":\"No image URL in DashScope response\"}";
            }

            // 下载图片并转为 base64（与 Spark 返回格式保持一致）
            log.info("ImageGenerationTool[qwen] downloading image from DashScope URL");
            String base64Image = downloadImageAsBase64(imageUrl);
            if (base64Image == null) {
                return "{\"error\":\"DOWNLOAD_ERROR\",\"message\":\"Failed to download generated image\"}";
            }

            // 从 usage 中获取实际宽高（如果有的话）
            int actualWidth = width, actualHeight = height;
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) respMap.get("usage");
            if (usage != null) {
                if (usage.get("width") instanceof Number) actualWidth = ((Number) usage.get("width")).intValue();
                if (usage.get("height") instanceof Number) actualHeight = ((Number) usage.get("height")).intValue();
            }

            Map<String, Object> result = Map.of(
                "image_base64", base64Image,
                "format", "png",
                "prompt", prompt,
                "width", actualWidth,
                "height", actualHeight,
                "image_url", imageUrl
            );
            return mapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("ImageGenerationTool[qwen] failed", e);
            return "{\"error\":\"EXECUTION_ERROR\",\"message\":\"" +
                e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 下载图片 URL 并转为 base64 编码字符串。
     * DashScope 返回的图片 URL 是临时的（有过期时间），需要及时下载。
     */
    private String downloadImageAsBase64(String imageUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.ALL));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // 使用 URI.create 避免 RestTemplate 对已编码的 OSS 预签名 URL 进行二次编码，
            // 否则 Signature 等查询参数会被重新编码导致 SignatureDoesNotMatch 403 错误
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                URI.create(imageUrl), HttpMethod.GET, entity, byte[].class);
            if (!resp.hasBody() || resp.getBody().length == 0) {
                log.warn("Downloaded image is empty: {}", imageUrl);
                return null;
            }
            return Base64.getEncoder().encodeToString(resp.getBody());
        } catch (Exception e) {
            log.error("Failed to download image from DashScope URL: {}", e.getMessage());
            return null;
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

        // 使用 UriComponentsBuilder 正确编码查询参数（空格 -> %20，而非 +）
        // 与官方 demo 的 HttpUrl.addQueryParameter 行为一致
        return UriComponentsBuilder.newInstance()
            .scheme(url.getProtocol())
            .host(host)
            .path(path)
            .queryParam("authorization", authorization)
            .queryParam("date", date)
            .queryParam("host", host)
            .build()
            .encode()
            .toUriString();
    }

    private Map<String, Object> buildSparkRequest(String prompt, int width, int height,
                                              String negativePrompt) {
        // header：与官方 demo 一致，包含 uid
        Map<String, Object> header = new java.util.LinkedHashMap<>();
        header.put("app_id", appId);
        header.put("uid", java.util.UUID.randomUUID().toString().substring(0, 15));

        // parameter.chat：只保留 API 文档中定义的参数
        Map<String, Object> chatParams = new java.util.LinkedHashMap<>();
        chatParams.put("domain", domain);
        chatParams.put("width", width);
        chatParams.put("height", height);

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
