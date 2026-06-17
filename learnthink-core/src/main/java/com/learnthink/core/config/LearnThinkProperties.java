package com.learnthink.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 提供者和模型预设的类型化配置，绑定自 {@code application.yml} 的 {@code learnthink} 前缀
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

    /** 对话模式："standard"（plan→generate 两阶段）或 "unified"（思考+工具+回复单次完成） */
    private String dialogueMode = "standard";
    /** 各AI提供者配置（deepseek/openai等），key 为提供者名称 */
    private Map<String, ProviderConfig> providers = new HashMap<>();
    /** 各模型预设配置（chat/reasoning/generation/unified），key 为预设名称 */
    private Map<String, ModelPreset> models = new HashMap<>();
    /** 讯飞 Spark 平台配置（图片生成等） */
    private SparkConfig spark = new SparkConfig();

    public String getDialogueMode() { return dialogueMode; }
    public void setDialogueMode(String dialogueMode) { this.dialogueMode = dialogueMode; }

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public Map<String, ModelPreset> getModels() { return models; }
    public void setModels(Map<String, ModelPreset> models) { this.models = models; }

    public SparkConfig getSpark() { return spark; }
    public void setSpark(SparkConfig spark) { this.spark = spark; }

    /** AI 提供者配置 */
    public static class ProviderConfig {
        /** API 基础地址，如 https://api.deepseek.com */
        private String baseUrl;
        /** API 密钥，支持环境变量引用如 ${DEEPSEEK_API_KEY} */
        private String apiKey;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    /** 模型预设配置 */
    public static class ModelPreset {
        /** 使用的提供者名称，对应 providers 中的 key */
        private String provider;
        /** 模型名称，如 deepseek-chat、gpt-4o */
        private String model;
        /** 温度参数，默认 0.7。thinkingEnabled=true 时被 API 忽略 */
        private double temperature = 0.7;
        /** 启用 DeepSeek 思维链模式。开启后温度参数由 API 忽略 */
        private boolean thinkingEnabled = false;
        /** 思维链努力程度："high" 或 "max"。仅 thinkingEnabled=true 时生效 */
        private String reasoningEffort;
        /** 模型输出的最大 Token 数。为 null 时使用 API 默认值 */
        private Integer maxTokens;

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
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    }

    /** 讯飞 Spark 平台配置 */
    public static class SparkConfig {
        private String appId;
        private String apiKey;
        private String apiSecret;
        private String domain;
        private String baseUrl = "https://maas-api.cn-huabei-1.xf-yun.com/v2.1/tti";

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiSecret() { return apiSecret; }
        public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
