package com.learnthink.core.directanswer.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标记块解析器：解析 [SECTION_NAME]...[/SECTION_NAME] 格式的 LLM 输出。
 * 支持三级容错：
 * 1. 精确匹配：按 [SECTION_NAME]...[/SECTION_NAME] 严格解析
 * 2. 宽松匹配：正则匹配，不区分大小写
 * 3. 兜底：全部解析失败时返回 raw text
 */
public class SectionMarkerProcessor {
    private static final Logger log = LoggerFactory.getLogger(SectionMarkerProcessor.class);

    /** 精确匹配模式 */
    private static final Pattern STRICT_PATTERN = Pattern.compile("\\[([^\\]]+)\\](.*?)\\[/\\1\\]", Pattern.DOTALL);

    /** 宽松匹配模式（不区分大小写，允许标记名变体） */
    private static final Pattern LOOSE_PATTERN = Pattern.compile(
        "\\[([a-z_]+)\\](.*?)\\[/([a-z_]+)\\]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * 解析带标记的文本，返回 sectionId → content 映射。
     * 自动尝试严格匹配 → 宽松匹配 → 兜底。
     */
    public Map<String, String> parse(String text) {
        return parse(text, false);
    }

    /**
     * 解析带标记的文本。
     * @param text LLM 原始输出
     * @param includeThinking 是否包含思考标记解析
     */
    public Map<String, String> parse(String text, boolean includeThinking) {
        if (text == null || text.isBlank()) return Map.of();

        // 1. 精确匹配
        Map<String, String> result = tryStrictParse(text);
        if (!result.isEmpty()) {
            log.debug("SectionMarkerProcessor: strict parse succeeded, {} sections", result.size());
            return result;
        }

        // 2. 宽松匹配
        result = tryLooseParse(text);
        if (!result.isEmpty()) {
            log.debug("SectionMarkerProcessor: loose parse succeeded, {} sections", result.size());
            return result;
        }

        // 3. 兜底：作为 raw text
        log.warn("SectionMarkerProcessor: all parsing failed, returning raw text");
        result = new LinkedHashMap<>();
        result.put("_raw", text);
        return result;
    }

    /**
     * 精确解析：按 [name]...[/name] 成对匹配。
     */
    private Map<String, String> tryStrictParse(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = STRICT_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1).trim().toLowerCase();
            String content = matcher.group(2).trim();
            if (!name.isEmpty()) {
                result.put(name, content);
            }
        }
        return result;
    }

    /**
     * 宽松解析：不区分大小写，允许标记名变体。
     */
    private Map<String, String> tryLooseParse(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        String normalized = text.toLowerCase().trim();

        // 手动扫描标记对
        List<String> knownMarkers = List.of(
            "answer_hero", "problem_analysis", "strategy_overview",
            "reasoning_chain", "method_summary", "error_warning",
            "prerequisite_knowledge");

        for (String marker : knownMarkers) {
            String openMark = "[" + marker + "]";
            String closeMark = "[/" + marker + "]";

            int start = normalized.indexOf(openMark);
            if (start < 0) continue;

            int end = normalized.indexOf(closeMark, start + openMark.length());
            if (end < 0) continue;

            String content = text.substring(start + openMark.length(), end).trim();
            result.put(marker, content);
            log.debug("SectionMarkerProcessor: loose match found for '{}'", marker);
        }
        return result;
    }
}
