package com.learnthink.core.tutoring.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.tutoring.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
@Component
public class ReActParser {
    private static final Logger log = LoggerFactory.getLogger(ReActParser.class);
    private static final Pattern THOUGHT_PATTERN = Pattern.compile("Thought:\\s*(.+?)(?=Action:|$)", Pattern.DOTALL);
    private static final Pattern ACTION_PATTERN = Pattern.compile("Action:\\s*(\\w+)");
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReActTurn parse(String accumulatedText) {
        String thought = extractThought(accumulatedText);
        String action = extractAction(accumulatedText);
        return new ReActTurn(thought, action, accumulatedText);
    }

    public ExecutionPlan parsePlan(String json) {
        try {
            return objectMapper.readValue(json, ExecutionPlan.class);
        } catch (Exception e) {
            log.warn("Failed to parse ExecutionPlan JSON, attempting lenient repair: {}", e.getMessage());
            String repaired = lenientRepair(json);
            try {
                return objectMapper.readValue(repaired, ExecutionPlan.class);
            } catch (Exception ex) {
                log.error("Failed to parse ExecutionPlan even after repair: {}", ex.getMessage());
                throw new RuntimeException("Failed to parse ExecutionPlan: " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * 从 LLM 输出中扫描出**首个完整且括号配对的** JSON 对象。
     * 采用基于索引的字符遍历，正确处理转义引号；遇到第一个平衡的 `}` 即返回，
     * 避免贪婪正则把多段 JSON（示例 + 实际）跨段截取。
     */
    public String extractJsonBlock(String text) {
        if (text == null) return null;
        int len = text.length();
        for (int start = 0; start < len; start++) {
            if (text.charAt(start) != '{') continue;
            int braceCount = 0;
            boolean inString = false;
            for (int i = start; i < len; i++) {
                char c = text.charAt(i);
                if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                    inString = !inString;
                    continue;
                }
                if (inString) continue;
                if (c == '{') braceCount++;
                else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
            // 当前 start 处未能闭合，外层 for 会尝试更靠后的 `{`
        }
        return null;
    }

    private String extractThought(String text) {
        Matcher m = THOUGHT_PATTERN.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private String extractAction(String text) {
        Matcher m = ACTION_PATTERN.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    String lenientRepair(String json) {
        String repaired = json;
        repaired = repaired.replaceAll("(?<!\\\\)'", "\"");
        repaired = repaired.replaceAll("//[^\n]*", "");
        repaired = repaired.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        repaired = repaired.replaceAll(",\\s*}", "}");
        repaired = repaired.replaceAll(",\\s*]", "]");
        return repaired;
    }

    public record ReActTurn(String thought, String action, String rawJson) {}
}
