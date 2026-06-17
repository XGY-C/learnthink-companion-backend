package com.learnthink.core.tutoring.diagram;

import com.learnthink.core.tutoring.domain.DiagramResult;
import com.learnthink.core.tutoring.domain.DiagramSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MermaidRenderer implements DiagramGenerator {
    private static final Logger log = LoggerFactory.getLogger(MermaidRenderer.class);

    @Override
    public String toolName() { return "mermaid"; }

    @Override
    public DiagramResult generate(DiagramSpec spec) {
        try {
            String mermaidDsl = generateMermaidDsl(spec);
            // Mermaid DSL 直接作为 inline content 返回，前端用 mermaid.render() 渲染
            return DiagramResult.generatedInline(spec.id(), mermaidDsl, 800, 600, "mermaid");
        } catch (Exception e) {
            log.warn("Mermaid generation failed for {}: {}", spec.id(), e.getMessage());
            return DiagramResult.degraded(spec.id(), e.getMessage(),
                generateFallbackText(spec), "mermaid");
        }
    }

    private String generateMermaidDsl(DiagramSpec spec) {
        String type = spec.type();
        String desc = spec.description();

        return switch (type) {
            case "flowchart" -> "graph TD\n  A[" + escapeMermaid(truncate(desc, 50)) + "] --> B[Step 2]\n  B --> C[Step 3]";
            case "sequence" -> "sequenceDiagram\n  participant A\n  participant B\n  A->>B: " + escapeMermaid(truncate(desc, 50));
            default -> "graph TD\n  A[" + escapeMermaid(truncate(desc, 50)) + "]";
        };
    }

    private String generateFallbackText(DiagramSpec spec) {
        return "**" + spec.type() + "**: " + spec.description();
    }

    private String escapeMermaid(String s) {
        return s.replace("\"", "&quot;").replace("[", "(").replace("]", ")");
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
