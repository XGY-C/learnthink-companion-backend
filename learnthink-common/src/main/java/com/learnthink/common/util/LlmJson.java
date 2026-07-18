package com.learnthink.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM 返回 JSON 的统一清洗工具。
 * <p>LLM 经常返回 markdown 代码块包裹的 JSON（{@code ```json ... ```}），
 * 或在 JSON 前后附带解释性文本。本工具负责剥离这些杂质，提取纯 JSON。</p>
 */
public final class LlmJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJson() {}

    /**
     * 从 LLM 原始返回中提取纯 JSON 字符串。
     * <p>处理顺序：
     * <ol>
     *   <li>去除 {@code ```json ... ```} 或 {@code ``` ... ```} 包裹</li>
     *   <li>若结果不以 {@code {} 或 {@code [} 开头，尝试截取第一个完整 JSON 结构</li>
     * </ol>
     *
     * @param raw LLM 原始返回文本
     * @return 纯 JSON 字符串；输入为空则返回空串
     */
    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.trim();

        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }

        if (s.startsWith("{") || s.startsWith("[")) return s;

        int brace = s.indexOf('{');
        int bracket = s.indexOf('[');
        int start = -1;
        char close = '}';

        if (brace >= 0 && (bracket < 0 || brace < bracket)) {
            start = brace;
            close = '}';
        } else if (bracket >= 0) {
            start = bracket;
            close = ']';
        }

        if (start >= 0) {
            int end = s.lastIndexOf(close);
            if (end > start) return s.substring(start, end + 1);
        }

        return s;
    }

    /**
     * 提取并解析为 {@link JsonNode}。
     *
     * @return 解析成功返回 JsonNode；失败或输入为空返回 {@code null}
     */
    public static JsonNode readTree(String raw) {
        try {
            String json = extract(raw);
            if (json.isBlank()) return null;
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
