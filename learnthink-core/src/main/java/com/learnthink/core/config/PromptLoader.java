package com.learnthink.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from classpath {@code resources/prompts/} files.
 * Prompts are cached after first load — restart to pick up changes.
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);
    private static final String BASE_PATH = "prompts/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Load a prompt by its path relative to {@code resources/prompts/}.
     * E.g. {@code get("agent/planner")} loads from {@code prompts/agent/planner.txt}.
     */
    public String get(String path) {
        return cache.computeIfAbsent(path, key -> {
            String resourcePath = BASE_PATH + key + ".txt";
            try {
                var resource = new ClassPathResource(resourcePath);
                try (var in = resource.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    log.debug("Loaded prompt: {}", resourcePath);
                    return content;
                }
            } catch (IOException e) {
                log.error("Failed to load prompt: {}", resourcePath, e);
                throw new RuntimeException("Prompt not found: " + resourcePath, e);
            }
        });
    }

    /** Convenience: {@code get("category/name")} */
    public String get(String category, String name) {
        return get(category + "/" + name);
    }
}
