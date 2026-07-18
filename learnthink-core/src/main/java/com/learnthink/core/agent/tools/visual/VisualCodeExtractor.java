package com.learnthink.core.agent.tools.visual;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 LLM 输出中提取代码块和 JSON 对象的工具类。
 * <p>
 * 移植自 DeepTutor 的 {@code extract_code_block} / {@code extract_json_object} /
 * {@code strip_outer_fence}，并增加了对 SVG/HTML 根标签的截取能力。
 */
public final class VisualCodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisualCodeExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 匹配带语言标签的代码围栏 */
    private static final Pattern FENCED_PATTERN = Pattern.compile(
        "```(?:([A-Za-z]+)\\s*\\n)?([\\s\\S]*?)\\n```", Pattern.MULTILINE);

    /** 匹配最外层代码围栏 */
    private static final Pattern OUTER_FENCE_PATTERN = Pattern.compile(
        "^```[A-Za-z]*\\s*\\n?([\\s\\S]*?)\\n?```$");

    /** 匹配 svg/xml 语言标签的代码围栏（用于 unfoldInlineSvg） */
    private static final Pattern SVG_FENCE_PATTERN = Pattern.compile(
        "```(?:svg|xml)\\s*\\n([\\s\\S]*?)\\n```", Pattern.CASE_INSENSITIVE);

    /** 匹配单个 SVG 标签块 */
    private static final Pattern SVG_BLOCK_PATTERN = Pattern.compile(
        "(<svg\\b[\\s\\S]*?</svg>)", Pattern.CASE_INSENSITIVE);

    /** 匹配 c- 色板类名 */
    private static final Pattern C_COLOR_CLASS_PATTERN = Pattern.compile(
        "\\bc-(?:gray|blue|teal|coral|pink|purple|green|amber|red)\\b");

    /**
     * SVG 主题 CSS（亮色模式），内嵌到 SVG 中使其脱离 DOM 环境后（如 .md 导出）也能正确渲染。
     * 与前端 {@code svg-theme.css} / {@code docxExport.ts::SVG_THEME_CSS} 保持同步。
     */
    private static final String SVG_THEME_CSS =
        "text{font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,\"Helvetica Neue\",Arial,sans-serif;dominant-baseline:central}" +
        "text.t,text.th{font-size:14px;fill:#1f2937}" +
        "text.th{font-weight:500}" +
        "text.ts{font-size:12px;fill:#6b7280}" +
        ".box{fill:#ffffff;stroke:#d1d5db;stroke-width:0.5}" +
        ".arr{stroke:#6b7280;stroke-width:1.5;fill:none!important;color:#6b7280}" +
        ".leader{stroke:#9ca3af;stroke-width:0.5;stroke-dasharray:3 3;fill:none!important;color:#9ca3af}" +
        ".c-gray rect,.c-gray circle,.c-gray ellipse,.c-gray polygon,.c-gray path,.c-gray line{fill:#f1efe8;stroke:#5f5e5a;color:#5f5e5a}" +
        ".c-gray text{fill:#2c2c2a}" +
        ".c-blue rect,.c-blue circle,.c-blue ellipse,.c-blue polygon,.c-blue path,.c-blue line{fill:#e6f1fb;stroke:#185fa5;color:#185fa5}" +
        ".c-blue text{fill:#0c447c}" +
        ".c-teal rect,.c-teal circle,.c-teal ellipse,.c-teal polygon,.c-teal path,.c-teal line{fill:#e1f5ee;stroke:#0f6e56;color:#0f6e56}" +
        ".c-teal text{fill:#085041}" +
        ".c-coral rect,.c-coral circle,.c-coral ellipse,.c-coral polygon,.c-coral path,.c-coral line{fill:#faece7;stroke:#993c1d;color:#993c1d}" +
        ".c-coral text{fill:#712b13}" +
        ".c-pink rect,.c-pink circle,.c-pink ellipse,.c-pink polygon,.c-pink path,.c-pink line{fill:#fbeaf0;stroke:#993556;color:#993556}" +
        ".c-pink text{fill:#72243e}" +
        ".c-purple rect,.c-purple circle,.c-purple ellipse,.c-purple polygon,.c-purple path,.c-purple line{fill:#eeedfe;stroke:#534ab7;color:#534ab7}" +
        ".c-purple text{fill:#3c3489}" +
        ".c-green rect,.c-green circle,.c-green ellipse,.c-green polygon,.c-green path,.c-green line{fill:#eaf3de;stroke:#3b6d11;color:#3b6d11}" +
        ".c-green text{fill:#27500a}" +
        ".c-amber rect,.c-amber circle,.c-amber ellipse,.c-amber polygon,.c-amber path,.c-amber line{fill:#faeeda;stroke:#854f0b;color:#854f0b}" +
        ".c-amber text{fill:#633806}" +
        ".c-red rect,.c-red circle,.c-red ellipse,.c-red polygon,.c-red path,.c-red line{fill:#fcebeb;stroke:#a32d2d;color:#a32d2d}" +
        ".c-red text{fill:#791f1f}";

    private VisualCodeExtractor() {}

    /**
     * 从 LLM 输出中提取指定语言的代码块。
     * 若没有匹配的围栏，返回去除首尾空白的原文。
     */
    public static String extractCodeBlock(String text, String language) {
        if (text == null || text.isBlank()) return "";

        // 优先匹配带语言标签的围栏
        if (language != null && !language.isBlank()) {
            Pattern langPattern = Pattern.compile(
                "```" + Pattern.quote(language) + "\\s*\\n([\\s\\S]*?)\\n```",
                Pattern.MULTILINE);
            Matcher m = langPattern.matcher(text);
            if (m.find()) return m.group(1).trim();
        }

        // 退而求其次，匹配任意围栏
        Matcher m = FENCED_PATTERN.matcher(text);
        if (m.find()) return m.group(2).trim();

        return text.trim();
    }

    /**
     * 从 LLM 输出中提取 JSON 对象。
     * 依次尝试：围栏 JSON -> 直接解析 -> 首个 { 到末尾 } 的子串。
     */
    public static Map<String, Object> extractJsonObject(String text) {
        if (text == null || text.isBlank()) return Map.of();

        // 尝试围栏中的 JSON
        Pattern jsonFence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
        Matcher m = jsonFence.matcher(text);
        String[] candidates = m.find() ? new String[]{m.group(1), text} : new String[]{text};

        for (String candidate : candidates) {
            try {
                return MAPPER.readValue(candidate.trim(), new TypeReference<>() {});
            } catch (Exception ignored) {}
        }

        // 尝试首个 { 到末尾 } 的子串
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end > start) {
            try {
                return MAPPER.readValue(text.substring(start, end + 1), new TypeReference<>() {});
            } catch (Exception ignored) {}
        }

        log.warn("Failed to extract JSON object from LLM output");
        return Map.of();
    }

    /**
     * 去除最外层的代码围栏（如果存在）。
     */
    public static String stripOuterFence(String text) {
        if (text == null) return "";
        Matcher m = OUTER_FENCE_PATTERN.matcher(text.trim());
        return m.matches() ? m.group(1).trim() : text.trim();
    }

    /**
     * 截取到根标签范围。
     * <p>
     * 防御性处理：LLM 偶尔在代码块外附加文字，或在闭合标签同行加围栏，
     * 此方法将输出截取到最外层根标签内。
     *
     * @param code       已提取的代码
     * @param renderType 渲染类型
     */
    public static String trimToRootTags(String code, String renderType) {
        if (code == null || code.isBlank()) return "";

        return switch (renderType) {
            case "svg" -> {
                String lower = code.toLowerCase();
                int start = lower.indexOf("<svg");
                int end = lower.lastIndexOf("</svg>");
                if (start != -1 && end > start) {
                    String svg = code.substring(start, end + "</svg>".length());
                    // 消除连续空行，防止前端 markdown-it 拆分 SVG 块
                    svg = svg.replaceAll("\\n\\s*\\n", "\n");
                    yield svg;
                }
                yield code;
            }
            case "html" -> {
                String lower = code.toLowerCase();
                int start = lower.indexOf("<!doctype");
                if (start == -1) start = lower.indexOf("<html");
                int end = lower.lastIndexOf("</html>");
                if (start != -1 && end > start) {
                    yield code.substring(start, end + "</html>".length());
                }
                yield code;
            }
            default -> code;
        };
    }

    /**
     * 将文档正文中被代码围栏包裹的 SVG 展开为内联 SVG。
     * <p>
     * LLM 生成文档正文时，偶尔会把配图 SVG 包在 {@code ```svg} 围栏中
     * （受 VisualPrompts 中 "SVG 用：```svg ... ```" 指示影响），
     * 导致前端将其渲染为代码块而非内联图。此方法检测含 {@code <svg>}
     * 的 svg/xml 围栏并去除围栏，使 SVG 作为裸 HTML 内联渲染。
     * <p>
     * 仅展开确实包含 {@code <svg} 和 {@code </svg>} 的围栏；
     * 不含 SVG 标签的 xml 代码块（如普通 XML 教学示例）不受影响。
     *
     * @param markdown LLM 生成的 Markdown 文档
     * @return 清洗后的 Markdown，SVG 围栏已展开为内联 SVG
     */
    public static String unfoldInlineSvg(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;
        Matcher m = SVG_FENCE_PATTERN.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1).trim();
            String lower = code.toLowerCase();
            if (lower.contains("<svg") && lower.contains("</svg>")) {
                // 展开围栏：用裸 SVG 替换代码块
                m.appendReplacement(sb, "\n\n" + Matcher.quoteReplacement(code) + "\n\n");
            } else {
                // 不含 SVG 标签，保留原始围栏（可能是 XML 教学代码）
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 向 Markdown 文档中所有使用色板类（c-*）但缺少 &lt;style&gt; 的 SVG 块注入主题 CSS，
     * 并将不兼容的 {@code stroke="context-stroke"} 替换为 {@code stroke="currentColor"}。
     * <p>
     * LLM 生成的 SVG 依赖页面 CSS（svg-theme.css）提供 .c-teal、.box、.arr 等类定义，
     * 脱离 DOM 环境（如导出 .md / .docx）后这些类失效。此方法在每个匹配的 SVG 块
     * 的 {@code <svg ...>} 标签后注入内联 {@code <style>}，使 SVG 自包含并独立渲染。
     * <p>
     * 同时将 marker 中的 {@code context-stroke}（SVG 2 草案，多数渲染器不支持）替换为
     * {@code currentColor}，配合注入 CSS 中的 {@code color} 属性使箭头颜色正确继承。
     *
     * @param markdown 包含内联 SVG 的 Markdown 文本
     * @return 处理后的 Markdown，SVG 已自包含样式
     */
    public static String injectSvgTheme(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;

        Matcher m = SVG_BLOCK_PATTERN.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String svg = m.group(1);
            if (C_COLOR_CLASS_PATTERN.matcher(svg).find()
                    && !svg.toLowerCase().contains("<style>")) {
                svg = svg.replaceFirst("(<svg\\b[^>]*>)",
                    "$1<style>" + SVG_THEME_CSS + "</style>");
            }
            svg = svg.replace("stroke=\"context-stroke\"", "stroke=\"currentColor\"");
            // 消除连续空行，防止前端 markdown-it 拆分 SVG 块
            svg = svg.replaceAll("\\n\\s*\\n", "\n");
            m.appendReplacement(sb, Matcher.quoteReplacement(svg));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
