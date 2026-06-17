package com.learnthink.core.tutoring.diagram;

import com.learnthink.core.config.TutoringConfig;
import com.learnthink.core.tutoring.domain.DiagramResult;
import com.learnthink.core.tutoring.domain.DiagramSpec;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DiagramOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(DiagramOrchestrator.class);

    private final Map<String, DiagramGenerator> generatorMap;
    private final TutoringConfig config;
    private final Semaphore semaphore;
    private final List<String> degradationChain = List.of(
        "image_model", "svg_direct", "mermaid");

    public DiagramOrchestrator(List<DiagramGenerator> generators, TutoringConfig config) {
        this.generatorMap = generators.stream()
            .collect(Collectors.toMap(DiagramGenerator::toolName, Function.identity()));
        this.config = config;
        this.semaphore = new Semaphore(config.getDiagram().getMaxConcurrency());
        log.info("DiagramOrchestrator initialized with generators: {}", generatorMap.keySet());
    }

    public void generateAsync(DiagramSpec spec, String sectionId, TutoringEventEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                semaphore.acquire();
                emitter.diagramQueued(spec.id(), sectionId);
                try {
                    DiagramResult result = generateWithDegradation(spec);
                    if ("generated".equals(result.status())) {
                        emitter.diagramDone(spec.id(), sectionId, result.url(), result.content(),
                            result.width() != null ? result.width() : 0,
                            result.height() != null ? result.height() : 0,
                            result.tool());
                    } else {
                        emitter.diagramDegraded(spec.id(), sectionId, result.fallbackText() != null ?
                            result.fallbackText() : "", result.fallbackText() != null ?
                            result.fallbackText() : "图解生成失败");
                    }
                } finally {
                    semaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Diagram generation interrupted for {}", spec.id());
            } catch (Exception e) {
                log.error("Diagram generation failed for {}: {}", spec.id(), e.getMessage());
                emitter.diagramDegraded(spec.id(), sectionId, e.getMessage(),
                    "图解生成失败: " + spec.description());
            }
        });
    }

    public void generateAll(List<DiagramSpec> specs, String sectionId, TutoringEventEmitter emitter) {
        if (specs == null || specs.isEmpty()) return;
        specs.stream()
            .sorted(Comparator.comparingInt(DiagramSpec::priority))
            .forEach(spec -> generateAsync(spec, sectionId, emitter));
    }

    private DiagramResult generateWithDegradation(DiagramSpec spec) {
        String preferredTool = spec.tool() != null ? spec.tool() : "svg_direct";
        String currentTool = preferredTool;

        // Level 0: Try preferred tool
        DiagramGenerator generator = generatorMap.get(currentTool);
        if (generator != null) {
            DiagramResult result = generator.generate(spec);
            if ("generated".equals(result.status())) return result;
            log.info("Level 0 failed for {} with {}, trying degradation", spec.id(), currentTool);
        }

        // Level 1: Retry with same tool (modified spec)
        if (generator != null) {
            DiagramResult result = generator.generate(spec);
            if ("generated".equals(result.status())) return result;
        }

        // Level 2: Try alternative tools
        for (String tool : degradationChain) {
            if (tool.equals(preferredTool)) continue;
            DiagramGenerator altGen = generatorMap.get(tool);
            if (altGen != null) {
                log.info("Trying degradation tool {} for {}", tool, spec.id());
                DiagramResult result = altGen.generate(spec);
                if ("generated".equals(result.status())) return result;
            }
        }

        // Level 3: Degrade to text
        log.warn("All diagram tools failed for {}, degrading to text", spec.id());
        return DiagramResult.degraded(spec.id(),
            "所有图解工具均失败，已降级为文字描述",
            "**" + spec.type() + "**: " + spec.description(),
            preferredTool);
    }
}
