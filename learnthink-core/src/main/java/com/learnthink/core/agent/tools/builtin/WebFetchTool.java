package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页抓取工具
 * <p>获取指定 URL 的网页内容，提取正文文本。</p>
 *
 * <p><b>当前为占位实现</b>：使用 JDK HttpClient 获取原始 HTML。
 * 建议后续引入 Jsoup 做正文提取，替换 {@link #extractContent} 方法。</p>
 */
public class WebFetchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "获取指定 URL 的网页内容并提取正文。" +
               "当 web_search 找到相关链接后，用此工具获取详细内容。" +
               "返回网页标题和正文文本（去除 HTML 标签）。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "要抓取的网页 URL（需包含 http:// 或 https://）"
                },
                "max_chars": {
                  "type": "integer",
                  "description": "返回正文最大字符数，默认 10000",
                  "default": 10000
                }
              },
              "required": ["url"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String url = String.valueOf(args.getOrDefault("url", "")).trim();
            int maxChars = args.containsKey("max_chars")
                    ? Math.min(Math.max(((Number) args.get("max_chars")).intValue(), 500), 50000)
                    : 10000;

            if (url.isEmpty()) {
                return errorJson("URL 不能为空");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return errorJson("URL 需包含 http:// 或 https:// 前缀");
            }

            log.info("WebFetchTool: url='{}', maxChars={}", url, maxChars);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "LearnThinkCompanion/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            // 提取标题和正文
            String title = extractTitle(html);
            String content = extractContent(html, maxChars);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", url);
            result.put("title", title);
            result.put("content", content);
            result.put("statusCode", response.statusCode());
            result.put("truncated", content.length() >= maxChars);
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("WebFetchTool failed", e);
            return errorJson("抓取失败: " + e.getMessage());
        }
    }

    /** 从 HTML 中提取 <title> 标签内容 */
    private String extractTitle(String html) {
        int start = html.toLowerCase().indexOf("<title>");
        int end = html.toLowerCase().indexOf("</title>");
        if (start >= 0 && end > start) {
            return html.substring(start + 7, end).trim();
        }
        return "";
    }

    /**
     * 从 HTML 中提取正文（简易实现）
     * <p>当前仅去除 HTML 标签，建议后续引入 Jsoup 做更好的正文提取。</p>
     */
    private String extractContent(String html, int maxChars) {
        // 去除 script 和 style 块
        String cleaned = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        cleaned = cleaned.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        // 去除所有 HTML 标签
        cleaned = cleaned.replaceAll("<[^>]+>", " ");
        // 压缩空白
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // 截断
        if (cleaned.length() > maxChars) {
            cleaned = cleaned.substring(0, maxChars) + "...[truncated]";
        }
        return cleaned;
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message, "content", ""));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
