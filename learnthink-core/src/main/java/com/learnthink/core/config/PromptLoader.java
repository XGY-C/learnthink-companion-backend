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
 * 从类路径 {@code resources/prompts/} 加载提示词模板
 * <p>提示词在首次加载后缓存，重启才能生效。</p>
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);
    private static final String BASE_PATH = "prompts/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 按相对于 {@code resources/prompts/} 的路径加载提示词
     * <p>例如 {@code get("agent/planner")} 从 {@code prompts/agent/planner.txt} 加载。</p>
     * @param path 提示词文件路径（不含后缀）
     * @return 提示词文本内容
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

    /** 便捷方法：{@code get("category/name")}，内部委托给 {@link #get(String)} */
    public String get(String category, String name) {
        return get(category + "/" + name);
    }
}
