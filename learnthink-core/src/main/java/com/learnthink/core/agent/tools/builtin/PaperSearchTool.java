package com.learnthink.core.agent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 论文搜索工具
 * <p>按关键词搜索 arXiv 预印本，返回论文元数据（标题、作者、摘要、URL）。</p>
 *
 * <p><b>当前为占位实现</b>：返回模拟数据。接入 arXiv API 后
 * 替换 {@link #doSearch} 方法即可。</p>
 *
 * <h3>接入 arXiv API</h3>
 * <pre>{@code
 * // arXiv API endpoint: http://export.arxiv.org/api/query?search_query=all:xxx&max_results=N
 * // 解析 Atom XML 响应，提取 entry 的 title/summary/authors/id
 * }</pre>
 */
public class PaperSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PaperSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "paper_search";
    }

    @Override
    public String description() {
        return "搜索 arXiv 学术预印本论文，返回标题、作者、摘要和 URL 等元数据。" +
               "适用于需要查找学术文献、了解前沿研究动态的场景。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "搜索关键词"
                },
                "max_results": {
                  "type": "integer",
                  "description": "返回论文数量（1-10），默认 3",
                  "default": 3
                },
                "years_limit": {
                  "type": "integer",
                  "description": "只返回最近 N 年的论文，默认 3",
                  "default": 3
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
                    : 3;
            int yearsLimit = args.containsKey("years_limit")
                    ? Math.min(Math.max(((Number) args.get("years_limit")).intValue(), 1), 10)
                    : 3;

            if (query.isEmpty()) {
                return errorJson("搜索关键词不能为空");
            }

            log.info("PaperSearchTool: query='{}', maxResults={}, yearsLimit={}", query, maxResults, yearsLimit);

            // ================================================================
            // 占位实现：返回模拟数据
            // 接入 arXiv API 后，替换 doSearch 方法
            // ================================================================
            List<Map<String, Object>> papers = doSearch(query, maxResults, yearsLimit);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("papers", papers);
            result.put("count", papers.size());
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("PaperSearchTool failed", e);
            return errorJson("论文搜索失败: " + e.getMessage());
        }
    }

    /**
     * 执行搜索（占位实现）
     * <p>接入 arXiv API 时替换此方法。</p>
     */
    private List<Map<String, Object>> doSearch(String query, int maxResults, int yearsLimit) {
        // 占位数据——接入 arXiv API 后删除
        return List.of(
                Map.of(
                        "title", "搜索结果占位 - " + query,
                        "authors", List.of("Author A", "Author B"),
                        "year", String.valueOf(java.time.Year.now().getValue()),
                        "arxivId", "2025.00001",
                        "url", "https://arxiv.org/abs/2025.00001",
                        "abstract", "这是论文搜索的占位结果。接入 arXiv API 后，" +
                                   "此处将返回与 '" + query + "' 相关的真实论文摘要。",
                        "source", "arxiv"
                )
        );
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "error", message,
                    "papers", List.of(),
                    "count", 0
            ));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
