package com.learnthink.core.agent.tools.visual;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 可视化代码本地校验器。
 * <p>
 * 移植自 DeepTutor 的 {@code validate_visualization}，提供零成本、确定性的
 * 渲染能力检查。校验失败时返回 LLM 可操作的错误描述，驱动一次定向修复。
 * <p>
 * 支持四种渲染类型：svg / chartjs / mermaid / html。
 */
public final class VisualValidator {

    private static final Logger log = LoggerFactory.getLogger(VisualValidator.class);

    /** SVG 非可见元素：defs / style / title / desc / metadata / script */
    private static final Set<String> SVG_NON_VISIBLE = Set.of(
        "defs", "style", "title", "desc", "metadata", "script");

    /** Mermaid 有效图表类型关键字 */
    private static final Set<String> MERMAID_KEYWORDS = Set.of(
        "graph", "flowchart", "sequencediagram", "classdiagram",
        "statediagram-v2", "statediagram", "erdiagram", "gantt",
        "mindmap", "pie", "journey", "gitgraph", "timeline",
        "quadrantchart", "requirementdiagram", "sankey-beta",
        "xychart-beta", "block-beta", "c4context"
    );

    /** HTML 文档特征 */
    private static final Pattern HTML_DOC_PATTERN = Pattern.compile(
        "<(?:html|!doctype|body|div)", Pattern.CASE_INSENSITIVE);

    private VisualValidator() {}

    /**
     * 校验可视化代码。
     *
     * @param code       LLM 生成的代码
     * @param renderType 渲染类型：svg / chartjs / mermaid / html
     * @return 校验结果
     */
    public static ValidationResult validate(String code, String renderType) {
        String text = code == null ? "" : code.trim();
        if (text.isEmpty()) {
            return ValidationResult.fail("Generated code is empty.");
        }

        return switch (renderType) {
            case "svg"      -> validateSvg(text);
            case "chartjs"  -> validateChartJs(text);
            case "mermaid"  -> validateMermaid(text);
            case "html"     -> validateHtml(text);
            case "mindmap"  -> validateMindmap(text);
            default         -> ValidationResult.success();
        };
    }

    // ── SVG 校验 ──────────────────────────────────────────────

    private static ValidationResult validateSvg(String text) {
        String lower = text.toLowerCase();
        if (!lower.contains("<svg")) {
            return ValidationResult.fail("SVG must contain a root <svg> element.");
        }

        // XML 格式校验（禁用外部实体防 XXE）
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(text)));
            Element root = doc.getDocumentElement();
            String tag = root.getTagName().split("}")[0].toLowerCase();
            if (!"svg".equals(tag)) {
                return ValidationResult.fail("Root element must be <svg>, found <" + tag + ">.");
            }
            // viewBox 必须用 camelCase，小写 viewbox 会被浏览器忽略导致图塌缩
            if (!root.hasAttribute("viewBox")) {
                return ValidationResult.fail(
                    "SVG root is missing a viewBox attribute (must be camelCase " +
                    "`viewBox`, required for responsive scaling).");
            }

            // 可见内容检查：统计 <svg> 下的直接子元素中非 defs/style 等辅助节点的数量
            int visibleCount = countVisibleElements(root);
            if (visibleCount == 0) {
                log.warn("SVG has no visible content (viewBox={}, codeLength={}) — "
                    + "SVG may have been truncated by the LLM API. "
                    + "code preview: {}", root.getAttribute("viewBox"), text.length(),
                    text.length() > 300 ? text.substring(0, 300) + "..." : text);
            }

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) {
                msg = msg.substring(0, 200);
            }
            return ValidationResult.fail("SVG is not well-formed XML: " + msg);
        }

        return ValidationResult.success();
    }

    // ── Chart.js 校验 ─────────────────────────────────────────

    private static ValidationResult validateChartJs(String text) {
        String candidate = VisualCodeExtractor.stripOuterFence(text);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = mapper.readValue(candidate, Object.class);
            if (!(parsed instanceof java.util.Map<?, ?> config)) {
                return ValidationResult.fail("Chart.js config must be a JSON object.");
            }
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (!config.containsKey("type")) missing.add("type");
            if (!config.containsKey("data")) missing.add("data");
            if (!missing.isEmpty()) {
                return ValidationResult.fail(
                    "Chart.js config is missing required field(s): " + String.join(", ", missing) + ".");
            }
        } catch (Exception e) {
            return ValidationResult.fail(
                "Chart.js config must be strict JSON: double-quoted keys, no " +
                "function callbacks, no comments, no trailing commas.");
        }
        return ValidationResult.success();
    }

    // ── Mermaid 校验 ──────────────────────────────────────────

    private static ValidationResult validateMermaid(String text) {
        String firstLine = "";
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                firstLine = trimmed;
                break;
            }
        }
        String lower = firstLine.toLowerCase();
        if (MERMAID_KEYWORDS.stream().anyMatch(lower::startsWith)
                || firstLine.startsWith("%%")
                || firstLine.startsWith("---")) {
            return ValidationResult.success();
        }
        return ValidationResult.fail(
            "Mermaid code must start with a valid diagram keyword (graph, " +
            "flowchart, sequenceDiagram, classDiagram, stateDiagram-v2, " +
            "erDiagram, gantt, mindmap, ...).");
    }

    // ── 思维导图校验 ──────────────────────────────────────────

    private static ValidationResult validateMindmap(String text) {
        String candidate = VisualCodeExtractor.stripOuterFence(text);
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = mapper.readValue(candidate, Object.class);
            if (!(parsed instanceof java.util.Map<?, ?> root)) {
                return ValidationResult.fail("Mindmap JSON must be an object with a 'root' key.");
            }
            if (!root.containsKey("root")) {
                return ValidationResult.fail("Mindmap JSON must contain a 'root' key.");
            }
            Object rootNode = root.get("root");
            if (!(rootNode instanceof java.util.Map<?, ?> rootMap)) {
                return ValidationResult.fail("'root' must be an object with 'text' and optional 'children'.");
            }
            if (!rootMap.containsKey("text")) {
                return ValidationResult.fail("'root' object must contain a 'text' field.");
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) msg = msg.substring(0, 200);
            return ValidationResult.fail("Mindmap output must be valid JSON: " + msg);
        }
        return ValidationResult.success();
    }

    // ── HTML 校验 ─────────────────────────────────────────────

    private static ValidationResult validateHtml(String text) {
        if (HTML_DOC_PATTERN.matcher(text.toLowerCase()).find()) {
            return ValidationResult.success();
        }
        return ValidationResult.fail("Output does not look like a renderable HTML document.");
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    /**
     * 统计 SVG 根元素下直接子元素中的可见元素数量。
     * 排除 defs、style、title、desc、metadata、script 等辅助节点及文本/注释节点。
     */
    private static int countVisibleElements(Element root) {
        int count = 0;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tagName = child.getNodeName().toLowerCase();
            // 去掉可能的命名空间前缀
            int colonIdx = tagName.indexOf(':');
            if (colonIdx >= 0) {
                tagName = tagName.substring(colonIdx + 1);
            }
            if (!SVG_NON_VISIBLE.contains(tagName)) {
                count++;
            }
        }
        return count;
    }
}
