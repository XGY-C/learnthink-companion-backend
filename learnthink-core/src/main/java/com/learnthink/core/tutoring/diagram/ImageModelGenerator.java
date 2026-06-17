package com.learnthink.core.tutoring.diagram;

import com.learnthink.core.tutoring.domain.DiagramResult;
import com.learnthink.core.tutoring.domain.DiagramSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImageModelGenerator implements DiagramGenerator {
    private static final Logger log = LoggerFactory.getLogger(ImageModelGenerator.class);

    @Override
    public String toolName() { return "image_model"; }

    @Override
    public DiagramResult generate(DiagramSpec spec) {
        // Image model generation requires external API (Spark AI or similar)
        // For now, degrade with fallback text as the actual API call needs
        // to be integrated with the existing ImageGenerationTool
        log.info("ImageModelGenerator called for diagram {} (type: {}), delegating to external API",
            spec.id(), spec.type());

        return DiagramResult.degraded(spec.id(),
            "图片模型生成暂未集成，请使用 svg_direct 或 mermaid",
            generateFallbackText(spec), "image_model");
    }

    private String generateFallbackText(DiagramSpec spec) {
        return "**[图解]** " + spec.type() + "\n\n" + spec.description();
    }
}
