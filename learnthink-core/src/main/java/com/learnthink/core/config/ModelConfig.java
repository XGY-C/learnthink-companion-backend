package com.learnthink.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 多提供者、多模型配置，支持可选 DeepSeek 思维链模式
 *
 * <p>每个提供者拥有自己的 {@link OpenAiApi}（延迟创建并缓存）。
 * 思维链模式参数通过 {@link OpenAiChatOptions#setExtraBody(Map)} 注入，
 * 该方式同时覆盖同步（{@code RestClient}）和流式（{@code WebClient}）路径——
 * 而 HTTP 拦截器仅覆盖同步路径。</p>
 *
 * <h3>预设 → Agent 映射</h3>
 * <table>
 *   <tr><th>预设</th><th>模型</th><th>思维链</th><th>使用者</th></tr>
 *   <tr><td>chat</td><td>deepseek-v4-flash</td><td>否</td><td>ChatServiceImpl, ConversationAgent</td></tr>
 *   <tr><td>reasoning</td><td>deepseek-v4-pro</td><td>是 (high)</td><td>CurriculumPlanner, ContentReviewer</td></tr>
 *   <tr><td>generation</td><td>deepseek-v4-flash</td><td>否</td><td>DocumentGenerator, ExerciseGenerator 等</td></tr>
 * </table>
 *
 * <h3>为什么 reasoning 启用思维链，chat/generation 不启用？</h3>
 * <ul>
 *   <li><b>CurriculumPlanner / ContentReviewer</b> — 复杂多步推理（计划结构化、事实验证）。
 *       思维链模式提高这些任务的准确性，且延迟可接受（在生成流水线中异步运行）。</li>
 *   <li><b>ChatServiceImpl / ConversationAgent</b> — 实时对话。
 *       思维链模式会增加 5-30 秒延迟，对聊天体验不可接受。</li>
 *   <li><b>Generators</b> — 创意内容生产。思维链模式禁用温度参数，
 *       而温度正是产生多样化、自然输出所需要的。</li>
 * </ul>
 *
 * <h3>使用方法</h3>
 * <pre>
 * // 对话聊天 — 快速、自然语气
 * {@code @Autowired @Qualifier("chatChatClientBuilder") ChatClient.Builder}
 *
 * // 规划和审查 — 深度推理带 CoT
 * {@code @Autowired @Qualifier("reasoningChatClientBuilder") ChatClient.Builder}
 *
 * // 内容生成 — 创意、适中温度
 * {@code @Autowired @Qualifier("generationChatClientBuilder") ChatClient.Builder}
 * </pre>
 */
@Configuration
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    private final LearnThinkProperties props;
    private final PromptLoader promptLoader;
    private final ConcurrentMap<String, OpenAiApi> apiCache = new ConcurrentHashMap<>();

    public ModelConfig(LearnThinkProperties props, PromptLoader promptLoader) {
        this.props = props;
        this.promptLoader = promptLoader;
    }

    /** 对话模型——聊天服务、画像对话。无思维链（延迟敏感） */
    @Bean
    @Primary
    public ChatClient.Builder chatChatClientBuilder() {
        return buildForPreset("chat");
    }

    /** 推理模型——CurriculumPlanner、ContentReviewer。启用思维链进行复杂推理 */
    @Bean
    public ChatClient.Builder reasoningChatClientBuilder() {
        return buildForPreset("reasoning");
    }

    /** 生成模型——内容生成器。无思维链（需要温度参数） */
    @Bean
    public ChatClient.Builder generationChatClientBuilder() {
        return buildForPreset("generation");
    }

    /** 统一模型——单次调用完成思考 + 工具调用 + 回复生成 */
    @Bean
    public ChatClient.Builder unifiedChatClientBuilder() {
        return buildForPreset("unified");
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

        // 构建选项：model、temperature、maxTokens、DeepSeek 思维链参数
        var optionsBuilder = OpenAiChatOptions.builder()
            .model(preset.getModel())
            .temperature(preset.getTemperature());

        if (preset.getMaxTokens() != null) {
            optionsBuilder.maxTokens(preset.getMaxTokens());
        }

        if (thinking && reasoningEffort != null && !reasoningEffort.isBlank()) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }

        var options = optionsBuilder.build();

        // 通过 extraBody 注入 DeepSeek 思维链模式
        // DeepSeek 默认启用思维链，但在缺少 reasoning_content 的工具调用后续场景中
        // 会导致 400 错误。我们对每个预设显式设置：
        //   reasoning → 启用（复杂任务使用 CoT）
        //   chat/generation → 禁用（延迟敏感、依赖温度参数）
        // 使用 extraBody（而非 HTTP 拦截器）确保同时覆盖 RestClient（同步）
        // 和 WebClient（流式）路径
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

    /**
     * 智能助手 ChatClient —— 结构化板书生成（旧版白板模式）
     * <p>使用 chat 预设（deepseek-v4-flash, temp=0.7），附加板书系统提示词，
     * 引导 AI 输出 JSON 数组格式的板书帧。</p>
     */
    @Bean("smartAssistantChatClient")
    public ChatClient smartAssistantChatClient(
            @Qualifier("chatChatClientBuilder") ChatClient.Builder builder) {
        return builder.clone()
                .defaultSystem(promptLoader.get("smart-assistant/board"))
                .build();
    }

    /**
     * 智能助手 ChatClient —— 视频讲解场景生成（新版 Scene 协议）
     * <p>使用 chat 预设，附加 Scene 系统提示词，
     * 引导 AI 输出 NDJSON 格式的场景帧，驱动前端 VideoLecturePlayer。</p>
     */
    @Bean("smartAssistantSceneChatClient")
    public ChatClient smartAssistantSceneChatClient(
            @Qualifier("chatChatClientBuilder") ChatClient.Builder builder) {
        return builder.clone()
                .defaultSystem(promptLoader.get("smart-assistant/scene"))
                .build();
    }
}
