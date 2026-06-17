package com.learnthink.core.tutoring.diagram;

import com.learnthink.core.tutoring.domain.DiagramResult;
import com.learnthink.core.tutoring.domain.DiagramSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 直接产出 SVG 字符串的占位生成器。
 * 真正的几何/图表布局应由 LLM 直接生成 SVG 字符串后再校验；
 * 此处先按 type 输出最小可视化骨架，并以 inline content 返回。
 * TODO: 接入 LLM SVG 生成 + 校验流水线。
 */
@Component
public class SvgDirectGenerator implements DiagramGenerator {
    private static final Logger log = LoggerFactory.getLogger(SvgDirectGenerator.class);

    @Override
    public String toolName() { return "svg_direct"; }

    @Override
    public DiagramResult generate(DiagramSpec spec) {
        try {
            String svg = generateSvg(spec);
            return DiagramResult.generatedInline(spec.id(), svg, 800, 600, "svg_direct");
        } catch (Exception e) {
            log.warn("SVG generation failed for {}: {}", spec.id(), e.getMessage());
            return DiagramResult.degraded(spec.id(), e.getMessage(),
                generateFallbackText(spec), "svg_direct");
        }
    }

    private String generateSvg(DiagramSpec spec) {
        String type = spec.type();
        String desc = spec.description();
        return switch (type) {
            case "data_flow", "flowchart" -> generateFlowSvg(desc);
            case "comparison_table" -> generateComparisonTableSvg(desc);
            case "svg_structure" -> generateStructureSvg(desc);
            default -> generateGenericSvg(desc);
        };
    }

    private String generateFlowSvg(String desc) {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 200">
              <rect x="20" y="60" width="180" height="80" rx="8" fill="#E3F2FD" stroke="#1565C0"/>
              <text x="110" y="105" text-anchor="middle" font-size="14" fill="#0D47A1">输入</text>
              <line x1="200" y1="100" x2="300" y2="100" stroke="#666" marker-end="url(#arrow)"/>
              <rect x="310" y="60" width="180" height="80" rx="8" fill="#FFF3E0" stroke="#E65100"/>
              <text x="400" y="105" text-anchor="middle" font-size="14" fill="#BF360C">处理</text>
              <line x1="490" y1="100" x2="590" y2="100" stroke="#666" marker-end="url(#arrow)"/>
              <rect x="600" y="60" width="180" height="80" rx="8" fill="#E8F5E9" stroke="#2E7D32"/>
              <text x="690" y="105" text-anchor="middle" font-size="14" fill="#1B5E20">输出</text>
              <text x="400" y="180" text-anchor="middle" font-size="12" fill="#555">%s</text>
              <defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#666"/></marker></defs>
            </svg>""".formatted(escapeXml(truncate(desc, 80)));
    }

    private String generateComparisonTableSvg(String desc) {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 240">
              <rect width="800" height="240" fill="white"/>
              <rect x="20" y="20" width="380" height="60" fill="#E3F2FD" stroke="#1565C0"/>
              <text x="210" y="55" text-anchor="middle" font-size="14" font-weight="bold" fill="#0D47A1">方案 A</text>
              <rect x="400" y="20" width="380" height="60" fill="#FFF3E0" stroke="#E65100"/>
              <text x="590" y="55" text-anchor="middle" font-size="14" font-weight="bold" fill="#BF360C">方案 B</text>
              <text x="400" y="180" text-anchor="middle" font-size="12" fill="#555">%s</text>
            </svg>""".formatted(escapeXml(truncate(desc, 150)));
    }

    private String generateStructureSvg(String desc) {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 320">
              <rect width="800" height="320" fill="white"/>
              <circle cx="400" cy="80" r="40" fill="#E3F2FD" stroke="#1565C0"/>
              <text x="400" y="85" text-anchor="middle" font-size="13" fill="#0D47A1">Root</text>
              <line x1="400" y1="120" x2="200" y2="200" stroke="#666"/>
              <line x1="400" y1="120" x2="600" y2="200" stroke="#666"/>
              <circle cx="200" cy="220" r="32" fill="#FFF3E0" stroke="#E65100"/>
              <text x="200" y="225" text-anchor="middle" font-size="12" fill="#BF360C">Node A</text>
              <circle cx="600" cy="220" r="32" fill="#E8F5E9" stroke="#2E7D32"/>
              <text x="600" y="225" text-anchor="middle" font-size="12" fill="#1B5E20">Node B</text>
              <text x="400" y="300" text-anchor="middle" font-size="12" fill="#555">%s</text>
            </svg>""".formatted(escapeXml(truncate(desc, 120)));
    }

    private String generateGenericSvg(String desc) {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 200">
              <rect width="800" height="200" fill="white"/>
              <rect x="20" y="20" width="760" height="160" rx="12" fill="#F8F9FA" stroke="#DEE2E6"/>
              <text x="400" y="110" text-anchor="middle" font-size="16" fill="#333">%s</text>
            </svg>""".formatted(escapeXml(truncate(desc, 200)));
    }

    private String generateFallbackText(DiagramSpec spec) {
        return "**" + spec.type() + "**: " + spec.description();
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
