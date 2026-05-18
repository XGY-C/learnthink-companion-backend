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
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Multi-provider, multi-model configuration with optional DeepSeek thinking mode.
 *
 * <p>Each provider gets its own {@link OpenAiApi} (lazily created, cached).
 * When a model preset has {@code thinking-enabled: true}, a separate API instance
 * is created that injects {@code {"thinking": {"type": "enabled"}}} and optional
 * {@code reasoning_effort} into every chat-completion request body.</p>
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

        // Separate API instances for thinking vs non-thinking (different HTTP interceptors)
        boolean thinking = preset.isThinkingEnabled();
        String cacheKey = providerName + (thinking ? ":thinking:" + preset.getReasoningEffort() : "");

        OpenAiApi api = apiCache.computeIfAbsent(cacheKey, key -> {
            log.info("Creating API client for provider '{}': base-url={}, thinking={}, reasoningEffort={}",
                providerName, provider.getBaseUrl(), thinking, preset.getReasoningEffort());

            var apiBuilder = OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey());

            if (thinking) {
                var restBuilder = RestClient.builder()
                    .requestInterceptor(thinkingInterceptor(preset.getReasoningEffort()));
                apiBuilder.restClientBuilder(restBuilder);
            }

            return apiBuilder.build();
        });

        log.info("Configuring '{}' model: provider={}, model={}, temperature={}, thinking={}",
            presetName, providerName, preset.getModel(), preset.getTemperature(), thinking);

        var chatModel = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(preset.getModel())
                .temperature(preset.getTemperature())
                .build())
            .build();
        return ChatClient.builder(chatModel);
    }

    /**
     * Creates an HTTP interceptor that injects DeepSeek thinking mode parameters
     * into chat-completion request bodies.
     *
     * <p>Only modifies requests to {@code /chat/completions} endpoints to avoid
     * corrupting embedding or other API calls.</p>
     */
    private ClientHttpRequestInterceptor thinkingInterceptor(String reasoningEffort) {
        return (request, body, execution) -> {
            String path = request.getURI().getPath();
            if (path != null && path.contains("/chat/completions")) {
                String jsonBody = new String(body, StandardCharsets.UTF_8);
                String modified = injectThinkingParams(jsonBody, reasoningEffort);
                log.debug("Injected thinking params into chat-completion request");
                return execution.execute(request, modified.getBytes(StandardCharsets.UTF_8));
            }
            return execution.execute(request, body);
        };
    }

    /** Injects {@code "thinking":{"type":"enabled"}} and optional {@code reasoning_effort}
     *  into the JSON request body just before the closing brace. */
    private String injectThinkingParams(String json, String reasoningEffort) {
        StringBuilder sb = new StringBuilder(json);
        int lastBrace = sb.lastIndexOf("}");
        if (lastBrace > 0) {
            StringBuilder extra = new StringBuilder(",\"thinking\":{\"type\":\"enabled\"}");
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                extra.append(",\"reasoning_effort\":\"").append(reasoningEffort).append("\"");
            }
            sb.insert(lastBrace, extra.toString());
        }
        return sb.toString();
    }
}
