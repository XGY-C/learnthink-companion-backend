package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具
 * <p>搜索网络并返回带引用的摘要结果。</p>
 *
 * <p><b>搜索策略（主备兜底）</b>：</p>
 * <ol>
 *   <li><b>主</b>：Tavily AI Search API —— 专为 AI Agent 设计，结果质量高，需 API Key</li>
 *   <li><b>备</b>：DuckDuckGo —— 免费，无需 API Key，Tavily 不可用时自动降级</li>
 * </ol>
 *
 * <p>当 Tavily API Key 未配置或调用失败时，自动降级到 DuckDuckGo。</p>
 */
public class WebSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final String DDG_HTML_URL = "https://html.duckduckgo.com/html/";
    private static final String DDG_INSTANT_URL = "https://api.duckduckgo.com/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient httpClient;

    /** Tavily API Key，为空表示未配置，将使用 DuckDuckGo 兜底 */
    private final String tavilyApiKey;

    /** 无参构造：未配置 Tavily，仅使用 DuckDuckGo */
    public WebSearchTool() {
        this("");
    }

    /**
     * 带参构造：优先使用 Tavily，兜底 DuckDuckGo
     *
     * @param tavilyApiKey Tavily API Key，为空时仅使用 DuckDuckGo
     */
    public WebSearchTool(String tavilyApiKey) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.tavilyApiKey = tavilyApiKey != null ? tavilyApiKey.trim() : "";
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "搜索网络获取最新信息，返回带引用的摘要结果。" +
               "当知识库中没有覆盖的问题出现、或需要最新事实时调用此工具。" +
               "返回结果包含标题、URL、摘要片段。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "搜索关键词（自然语言）"
                },
                "max_results": {
                  "type": "integer",
                  "description": "返回结果数量（1-10），默认 5",
                  "default": 5
                }
              },
              "required": ["query"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String query = String.valueOf(args.getOrDefault("query", "")).trim();
            int maxResults = args.containsKey("max_results")
                    ? Math.min(Math.max(((Number) args.get("max_results")).intValue(), 1), 10)
                    : 5;

            if (query.isEmpty()) {
                return errorJson("搜索关键词不能为空");
            }

            log.info("WebSearchTool: query='{}', maxResults={}", query, maxResults);

            List<Map<String, Object>> results = doSearch(query, maxResults);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("results", results);
            response.put("count", results.size());
            return MAPPER.writeValueAsString(response);

        } catch (Exception e) {
            log.error("WebSearchTool failed", e);
            return errorJson("搜索失败: " + e.getMessage());
        }
    }

    // ================================================================
    // 搜索调度：Tavily 优先，DuckDuckGo 兜底
    // ================================================================

    /**
     * 执行搜索
     * <p>优先 Tavily，失败或未配置时降级到 DuckDuckGo。</p>
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回结果数
     * @return 搜索结果列表
     */
    private List<Map<String, Object>> doSearch(String query, int maxResults) {
        // 1. 优先尝试 Tavily
        if (!tavilyApiKey.isEmpty()) {
            try {
                List<Map<String, Object>> tavilyResults = searchWithTavily(query, maxResults);
                if (!tavilyResults.isEmpty()) {
                    log.info("WebSearchTool: Tavily 搜索成功，返回 {} 条结果", tavilyResults.size());
                    return tavilyResults;
                }
                log.warn("WebSearchTool: Tavily 返回空结果，降级到 DuckDuckGo");
            } catch (Exception e) {
                log.warn("WebSearchTool: Tavily 搜索失败，降级到 DuckDuckGo: {}", e.getMessage());
            }
        }

        // 2. 兜底：DuckDuckGo
        return searchWithDuckDuckGo(query, maxResults);
    }

    // ================================================================
    // Tavily AI Search
    // ================================================================

    /**
     * 调用 Tavily AI Search API
     * <p>API 地址: POST https://api.tavily.com/search</p>
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回结果数
     * @return 搜索结果列表
     */
    private List<Map<String, Object>> searchWithTavily(String query, int maxResults) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", query);
        requestBody.put("max_results", maxResults);
        requestBody.put("search_depth", "basic");
        requestBody.put("include_answer", false);

        String jsonBody = MAPPER.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tavilyApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Tavily API 返回状态码 " + response.statusCode() +
                    "，响应: " + response.body().substring(0, Math.min(response.body().length(), 200)));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> json = MAPPER.readValue(response.body(), Map.class);

        Object resultsObj = json.get("results");
        if (!(resultsObj instanceof List<?> rawList)) {
            return List.of();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> itemMap) {
                String title = toStr(itemMap.get("title"));
                String url = toStr(itemMap.get("url"));
                String content = toStr(itemMap.get("content"));

                if (title.isEmpty() && content.isEmpty()) {
                    continue;
                }

                results.add(Map.of(
                        "title", title.isEmpty() ? "(无标题)" : title,
                        "url", url,
                        "snippet", content,
                        "source", "tavily"
                ));
            }
        }

        return results;
    }

    // ================================================================
    // DuckDuckGo 兜底搜索
    // ================================================================

    /**
     * DuckDuckGo 搜索（兜底方案）
     * <p>组合 Instant Answer API 和 HTML 搜索结果。</p>
     */
    private List<Map<String, Object>> searchWithDuckDuckGo(String query, int maxResults) {
        List<Map<String, Object>> results = new ArrayList<>();

        // 1. 尝试 Instant Answer API（获取即时答案）
        try {
            Map<String, Object> instantAnswer = fetchInstantAnswer(query);
            if (instantAnswer != null) {
                results.add(instantAnswer);
                log.debug("WebSearchTool: DuckDuckGo Instant Answer 获取成功");
            }
        } catch (Exception e) {
            log.warn("WebSearchTool: DuckDuckGo Instant Answer 失败: {}", e.getMessage());
        }

        // 2. 抓取 HTML 搜索结果
        try {
            List<Map<String, Object>> htmlResults = fetchHtmlResults(query, maxResults - results.size());
            results.addAll(htmlResults);
            log.debug("WebSearchTool: DuckDuckGo HTML 搜索返回 {} 条结果", htmlResults.size());
        } catch (Exception e) {
            log.warn("WebSearchTool: DuckDuckGo HTML 搜索失败: {}", e.getMessage());
        }

        // 截断到 maxResults
        if (results.size() > maxResults) {
            results = results.subList(0, maxResults);
        }

        log.info("WebSearchTool: DuckDuckGo 兜底搜索完成，共 {} 条结果", results.size());
        return results;
    }

    /**
     * 调用 DuckDuckGo Instant Answer API
     * <p>API 地址: https://api.duckduckgo.com/?q=QUERY&format=json&no_html=1</p>
     *
     * @return 即时答案结果，无答案时返回 null
     */
    private Map<String, Object> fetchInstantAnswer(String query) throws Exception {
        String url = DDG_INSTANT_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) +
                     "&format=json&no_html=1&skip_disambig=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> json = MAPPER.readValue(response.body(), Map.class);

        String abstractText = (String) json.get("AbstractText");
        String abstractSource = (String) json.get("AbstractSource");
        String abstractUrl = (String) json.get("AbstractURL");
        String heading = (String) json.get("Heading");

        // 优先使用 Abstract
        if (abstractText != null && !abstractText.isBlank()) {
            return Map.of(
                    "title", heading != null ? heading : abstractSource,
                    "url", abstractUrl != null ? abstractUrl : "",
                    "snippet", abstractText,
                    "source", "duckduckgo_instant_answer"
            );
        }

        // 尝试从 RelatedTopics 中提取第一条
        Object relatedTopics = json.get("RelatedTopics");
        if (relatedTopics instanceof List<?> topics && !topics.isEmpty()) {
            for (Object topic : topics) {
                if (topic instanceof Map<?, ?> topicMap) {
                    String text = (String) topicMap.get("Text");
                    Object firstUrl = topicMap.get("FirstURL");
                    if (text != null && !text.isBlank()) {
                        return Map.of(
                                "title", heading != null ? heading : query,
                                "url", firstUrl != null ? firstUrl.toString() : "",
                                "snippet", text,
                                "source", "duckduckgo_instant_answer"
                        );
                    }
                }
            }
        }

        return null;
    }

    /**
     * 抓取 DuckDuckGo HTML 搜索页面并解析结果
     * <p>搜索地址: https://html.duckduckgo.com/html/?q=QUERY</p>
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回结果数
     * @return 搜索结果列表
     */
    private List<Map<String, Object>> fetchHtmlResults(String query, int maxResults) throws Exception {
        if (maxResults <= 0) {
            return List.of();
        }

        // 使用 POST 表单提交，避免 URL 编码问题
        String formBody = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) +
                          "&kl=wt-wt"; // wt-wt = 不限地区

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DDG_HTML_URL))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("WebSearchTool: DuckDuckGo HTML 搜索返回状态码 {}", response.statusCode());
            return List.of();
        }

        return parseHtmlResults(response.body(), maxResults);
    }

    /**
     * 使用 Jsoup 解析 DuckDuckGo HTML 搜索结果页面
     */
    private List<Map<String, Object>> parseHtmlResults(String html, int maxResults) {
        List<Map<String, Object>> results = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // DuckDuckGo HTML 版结果在 <div class="result ..."> 中
        Elements resultDivs = doc.select("div.result, div.results_links, div.web-result");

        for (Element resultDiv : resultDivs) {
            if (results.size() >= maxResults) {
                break;
            }

            // 标题和链接: <a class="result__a" href="...">
            Element titleLink = resultDiv.selectFirst("a.result__a");
            if (titleLink == null) {
                continue;
            }

            String title = titleLink.text().trim();
            String rawHref = titleLink.attr("href");

            // DuckDuckGo 链接格式: //duckduckgo.com/l/?uddg=ENCODED_URL&rut=...
            // 需要提取 uddg 参数获取真实 URL
            String url = extractRealUrl(rawHref);
            if (url.isEmpty()) {
                url = rawHref;
                if (url.startsWith("//")) {
                    url = "https:" + url;
                }
            }

            // 摘要: <a class="result__snippet"> 或 <div class="result__snippet">
            Element snippetElem = resultDiv.selectFirst(".result__snippet");
            String snippet = snippetElem != null ? snippetElem.text().trim() : "";

            if (title.isEmpty() && snippet.isEmpty()) {
                continue;
            }

            results.add(Map.of(
                    "title", title.isEmpty() ? "(无标题)" : title,
                    "url", url,
                    "snippet", snippet,
                    "source", "duckduckgo_html"
            ));
        }

        return results;
    }

    /**
     * 从 DuckDuckGo 重定向链接中提取真实 URL
     * <p>链接格式: //duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com&rut=...</p>
     *
     * @param rawHref 原始 href
     * @return 解码后的真实 URL，解析失败返回空字符串
     */
    private String extractRealUrl(String rawHref) {
        if (rawHref == null || rawHref.isEmpty()) {
            return "";
        }

        // 查找 uddg 参数
        int uddgIdx = rawHref.indexOf("uddg=");
        if (uddgIdx < 0) {
            return "";
        }

        int start = uddgIdx + 5;
        int end = rawHref.indexOf("&", start);
        if (end < 0) {
            end = rawHref.length();
        }

        String encodedUrl = rawHref.substring(start, end);
        return URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8);
    }

    /** 安全转字符串，null 返回空串 */
    private static String toStr(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "error", message,
                    "results", List.of(),
                    "count", 0
            ));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
