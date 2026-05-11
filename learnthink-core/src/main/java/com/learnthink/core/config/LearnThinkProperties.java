package com.learnthink.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Typed configuration for providers and model presets, bound from
 * {@code application.yml} under the {@code learnthink} prefix.
 *
 * <pre>
 * learnthink:
 *   providers:
 *     deepseek:
 *       base-url: https://api.deepseek.com
 *       api-key: ${DEEPSEEK_API_KEY}
 *     openai:
 *       base-url: https://api.openai.com
 *       api-key: ${OPENAI_API_KEY}
 *   models:
 *     chat:
 *       provider: deepseek
 *       model: deepseek-chat
 *       temperature: 0.7
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "learnthink")
public class LearnThinkProperties {

    private Map<String, ProviderConfig> providers = new HashMap<>();
    private Map<String, ModelPreset> models = new HashMap<>();

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public Map<String, ModelPreset> getModels() { return models; }
    public void setModels(Map<String, ModelPreset> models) { this.models = models; }

    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public static class ModelPreset {
        private String provider;
        private String model;
        private double temperature = 0.7;
        /** Enable DeepSeek thinking mode (CoT). When true, temperature is ignored by the API. */
        private boolean thinkingEnabled = false;
        /** Thinking effort: "high" or "max". Only used when thinkingEnabled=true. */
        private String reasoningEffort;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public boolean isThinkingEnabled() { return thinkingEnabled; }
        public void setThinkingEnabled(boolean thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }
        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    }
}
