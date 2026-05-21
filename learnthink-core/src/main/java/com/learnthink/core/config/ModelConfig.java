package com.learnthink.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Multi-provider, multi-model configuration with optional DeepSeek thinking mode.
 *
 * <p>Each provider gets its own {@link OpenAiApi} (lazily created, cached).
 * Thinking mode params are injected via {@link OpenAiChatOptions#setExtraBody(Map)}
 * which applies to both sync ({@code RestClient}) and streaming ({@code WebClient})
 * paths — unlike HTTP interceptors which only cover the sync path.</p>
 *
 * <h3>Preset → Agent mapping</h3>
 * <table>
 *   <tr><th>Preset</th><th>Model</th><th>Thinking</th><th>Used by</th></tr>
 *   <tr><td>chat</td><td>deepseek-v4-flash</td><td>No</td><td>ChatServiceImpl, ConversationAgent</td></tr>
 *   <tr><td>reasoning</td><td>deepseek-v4-pro</td><td>Yes (high)</td><td>CurriculumPlanner, ContentReviewer</td></tr>
 *   <tr><td>generation</td><td>deepseek-v4-flash</td><td>No</td><td>DocumentGenerator, ExerciseGenerator, etc.</td></tr>
 * </table>
 *
 * <h3>Why thinking for reasoning, not for chat/generation?</h3>
 * <ul>
 *   <li><b>CurriculumPlanner / ContentReviewer</b> — complex multi-step reasoning (plan
 *       structuring, fact verification). Thinking mode improves accuracy on these
 *       tasks and latency is acceptable since they run asynchronously in the
 *       generation pipeline.</li>
 *   <li><b>ChatServiceImpl / ConversationAgent</b> — real-time conversation.
 *       Thinking mode would add 5-30s latency, unacceptable for chat UX.</li>
 *   <li><b>Generators</b> — creative content production. Thinking mode disables
 *       temperature, which is needed for varied, natural-feeling output.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 * // conversational chat — fast, natural tone
 * {@code @Autowired @Qualifier("chatChatClientBuilder") ChatClient.Builder}
 *
 * // planning & review — deep reasoning with CoT
 * {@code @Autowired @Qualifier("reasoningChatClientBuilder") ChatClient.Builder}
 *
 * // content generation — creative, moderate temperature
 * {@code @Autowired @Qualifier("generationChatClientBuilder") ChatClient.Builder}
 * </pre>
 */
@Configuration
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    private final LearnThinkProperties props;
    private final ConcurrentMap<String, OpenAiApi> apiCache = new ConcurrentHashMap<>();

    public ModelConfig(LearnThinkProperties props) {
        this.props = props;
    }

    /** Conversational model — chat service, profile chat. No thinking (latency-critical). */
    @Bean
    @Primary
    public ChatClient.Builder chatChatClientBuilder() {
        return buildForPreset("chat");
    }

    /** Reasoning model — CurriculumPlanner, ContentReviewer. Thinking enabled for complex reasoning. */
    @Bean
    public ChatClient.Builder reasoningChatClientBuilder() {
        return buildForPreset("reasoning");
    }

    /** Generation model — content generators. No thinking (temperature required). */
    @Bean
    public ChatClient.Builder generationChatClientBuilder() {
        return buildForPreset("generation");
    }

    private ChatClient.Builder buildForPreset(String presetName) {
        var preset = props.getModels().get(presetName);
        if (preset == null) {
            throw new IllegalStateException(
                "Model preset '" + presetName + "' not found in learnthink.models config");
        }
        String providerName = preset.getProvider();
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalStateException(
                "Model preset '" + presetName + "' must specify a provider");
        }
        var provider = props.getProviders().get(providerName);
        if (provider == null) {
            throw new IllegalStateException(
                "Provider '" + providerName + "' not found in learnthink.providers config");
        }

        boolean thinking = preset.isThinkingEnabled();
        String reasoningEffort = preset.getReasoningEffort();
        String cacheKey = providerName + ":thinking:" + (thinking ? reasoningEffort : "disabled");

        OpenAiApi api = apiCache.computeIfAbsent(cacheKey, key -> {
            log.info("Creating API client for provider '{}': base-url={}, thinking={}, reasoningEffort={}",
                providerName, provider.getBaseUrl(), thinking, reasoningEffort);

            return OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .build();
        });

        // Build options: model, temperature, DeepSeek thinking params
        var optionsBuilder = OpenAiChatOptions.builder()
            .model(preset.getModel())
            .temperature(preset.getTemperature());

        if (thinking && reasoningEffort != null && !reasoningEffort.isBlank()) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }

        var options = optionsBuilder.build();

        // Inject DeepSeek thinking mode via extraBody.
        // DeepSeek defaults thinking to ENABLED, which causes 400 on tool-call
        // follow-ups when reasoning_content is absent. We explicitly set it for
        // every preset:
        //   reasoning → enabled (CoT for complex tasks)
        //   chat/generation → disabled (latency-critical, temperature-dependent)
        // Using extraBody (not an HTTP interceptor) ensures it applies to both
        // RestClient (sync) and WebClient (streaming) paths.
        Map<String, Object> extraBody = new HashMap<>();
        extraBody.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        options.setExtraBody(extraBody);

        log.info("Configuring '{}' model: provider={}, model={}, temperature={}, thinking={}",
            presetName, providerName, preset.getModel(), preset.getTemperature(), thinking);

        var chatModel = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build();
        return ChatClient.builder(chatModel);
    }
}
