package com.learnthink.core.agent.tools;

import com.learnthink.core.agent.impl.BookInfoTool;
import com.learnthink.core.agent.impl.ImageGenerationTool;
import com.learnthink.core.agent.impl.RagTool;
import com.learnthink.core.agent.tools.builtin.*;
import com.learnthink.core.agent.tools.visual.SmartSvgTool;
import com.learnthink.core.agent.tools.visual.GenerateChartTool;
import com.learnthink.core.agent.tools.visual.GenerateMermaidTool;
import com.learnthink.core.agent.tools.visual.GenerateHtmlTool;
import com.learnthink.core.agent.tools.visual.GenerateVisualizationTool;
import com.learnthink.core.agent.tools.visual.GenerateThreeJsTool;
import com.learnthink.core.agent.tools.visual.GenerateMindmapTool;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.service.Judge0Client;
import com.learnthink.core.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 工具自动注册配置
 * <p>在 Spring 容器启动后，自动将所有工具实例注册到 {@link ToolRegistry}。</p>
 *
 * <h3>注册的工具</h3>
 * <table>
 *   <tr><th>工具名</th><th>分组</th><th>依赖</th></tr>
 *   <tr><td>rag_retrieve</td><td>CONTEXT_GATED</td><td>RagTool（已有）</td></tr>
 *   <tr><td>get_book_info</td><td>CONTEXT_GATED</td><td>BookInfoTool（已有）</td></tr>
 *   <tr><td>code_execution</td><td>CONTEXT_GATED</td><td>Judge0Client</td></tr>
 *   <tr><td>web_search</td><td>USER_TOGGLEABLE</td><td>Tavily Key（可选，兜底 DuckDuckGo）</td></tr>
 *   <tr><td>web_fetch</td><td>ALWAYS_ON</td><td>无</td></tr>
 *   <tr><td>reason</td><td>USER_TOGGLEABLE</td><td>ChatClient</td></tr>
 *   <tr><td>brainstorm</td><td>USER_TOGGLEABLE</td><td>ChatClient</td></tr>
 *   <tr><td>ask_user</td><td>ALWAYS_ON</td><td>无</td></tr>
 *   <tr><td>read_profile</td><td>CONTEXT_GATED</td><td>ProfileService</td></tr>
 *   <tr><td>write_profile</td><td>ALWAYS_ON</td><td>无</td></tr>
 *   <tr><td>read_learning_path</td><td>CONTEXT_GATED</td><td>无（占位）</td></tr>
 *   <tr><td>paper_search</td><td>USER_TOGGLEABLE</td><td>无（占位）</td></tr>
 *   <tr><td>cron_tool</td><td>ALWAYS_ON</td><td>无</td></tr>
 * </table>
 *
 * <p><b>注意</b>：RagTool 和 BookInfoTool 需要 courseId，属于按次构造的工具，
 * 此处注册的是共享实例，实际调用时通过 ToolCallbackFactory 注入 courseId。</p>
 */
@Configuration
public class ToolRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrationConfig.class);

    private final ToolRegistry registry;
    private final Judge0Client judge0Client;
    private final ProfileService profileService;
    private final ChatClient.Builder chatClientBuilder;
    private final PromptLoader promptLoader;

    // 现有工具（可选注入，可能不存在）
    private final RagTool ragTool;
    private final BookInfoTool bookInfoTool;

    // Smart v2 可视化工具
    private final SmartSvgTool smartSvgTool;
    private final GenerateChartTool generateChartTool;
    private final GenerateMermaidTool generateMermaidTool;
    private final GenerateHtmlTool generateHtmlTool;
    private final GenerateVisualizationTool generateVisualizationTool;
    private final GenerateThreeJsTool generateThreeJsTool;
    private final GenerateMindmapTool generateMindmapTool;
    private final ImageGenerationTool imageGenerationTool;

    // Tavily 搜索 API Key（可选，未配置时 WebSearchTool 自动降级到 DuckDuckGo）
    private final String tavilyApiKey;

    public ToolRegistrationConfig(ToolRegistry registry,
                                   Judge0Client judge0Client,
                                   ProfileService profileService,
                                   ChatClient.Builder chatClientBuilder,
                                   PromptLoader promptLoader,
                                   org.springframework.beans.factory.ObjectProvider<RagTool> ragToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<BookInfoTool> bookInfoToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<SmartSvgTool> smartSvgToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<GenerateChartTool> generateChartToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<GenerateMermaidTool> generateMermaidToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<GenerateHtmlTool> generateHtmlToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<GenerateVisualizationTool> generateVisualizationToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<GenerateThreeJsTool> generateThreeJsToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<GenerateMindmapTool> generateMindmapToolProvider,
                                   org.springframework.beans.factory.ObjectProvider<ImageGenerationTool> imageGenerationToolProvider,
                                   @Value("${tavily.api-key:}") String tavilyApiKey) {
        this.registry = registry;
        this.judge0Client = judge0Client;
        this.profileService = profileService;
        this.chatClientBuilder = chatClientBuilder;
        this.promptLoader = promptLoader;
        this.ragTool = ragToolProvider.getIfAvailable();
        this.bookInfoTool = bookInfoToolProvider.getIfAvailable();
        this.smartSvgTool = smartSvgToolProvider.getIfAvailable();
        this.generateChartTool = generateChartToolProvider.getIfAvailable();
        this.generateMermaidTool = generateMermaidToolProvider.getIfAvailable();
        this.generateHtmlTool = generateHtmlToolProvider.getIfAvailable();
        this.generateVisualizationTool = generateVisualizationToolProvider.getIfAvailable();
        this.generateThreeJsTool = generateThreeJsToolProvider.getIfAvailable();
        this.generateMindmapTool = generateMindmapToolProvider.getIfAvailable();
        this.imageGenerationTool = imageGenerationToolProvider.getIfAvailable();
        this.tavilyApiKey = tavilyApiKey;
    }

    @PostConstruct
    public void registerAllTools() {
        log.info("=== 开始注册 Agent 工具 ===");

        // ── 现有工具注册到统一注册表 ──
        if (ragTool != null) {
            registry.register(ragTool, ToolGroup.CONTEXT_GATED);
        } else {
            log.warn("RagTool 未注入，跳过注册");
        }

        if (bookInfoTool != null) {
            registry.register(bookInfoTool, ToolGroup.CONTEXT_GATED);
        } else {
            log.warn("BookInfoTool 未注入，跳过注册");
        }

        // ── 新增工具 ──

        // 代码执行（复用 Judge0）
        registry.register(new CodeExecutionTool(judge0Client), ToolGroup.CONTEXT_GATED);

        // 网络搜索（Tavily 优先，DuckDuckGo 兜底）
        registry.register(new WebSearchTool(tavilyApiKey), ToolGroup.USER_TOGGLEABLE);

        // 网页抓取
        registry.register(new WebFetchTool(), ToolGroup.ALWAYS_ON);

        // 深度推理（使用独立 ChatClient）
        ChatClient reasonClient = chatClientBuilder.build();
        registry.register(new ReasonTool(reasonClient), ToolGroup.USER_TOGGLEABLE);

        // 头脑风暴
        registry.register(new BrainstormTool(reasonClient), ToolGroup.USER_TOGGLEABLE);

        // 向用户提问
        registry.register(new AskUserTool(), ToolGroup.ALWAYS_ON);

        // 读取画像（复用 ProfileService）
        registry.register(new ReadProfileTool(profileService), ToolGroup.CONTEXT_GATED);

        // 写入画像
        registry.register(new WriteProfileTool(), ToolGroup.ALWAYS_ON);

        // 读取学习路径（占位实现）
        registry.register(new ReadLearningPathTool(), ToolGroup.CONTEXT_GATED);

        // 论文搜索（占位实现）
        registry.register(new PaperSearchTool(), ToolGroup.USER_TOGGLEABLE);

        // 定时任务
        registry.register(new CronTool(), ToolGroup.ALWAYS_ON);

        // ── Smart v2 可视化工具（三阶段流水线）──
        if (smartSvgTool != null) {
            registry.register(smartSvgTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("SmartSvgTool 未注入，跳过注册");
        }
        if (generateChartTool != null) {
            registry.register(generateChartTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("GenerateChartTool 未注入，跳过注册");
        }
        if (generateMermaidTool != null) {
            registry.register(generateMermaidTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("GenerateMermaidTool 未注入，跳过注册");
        }
        if (generateHtmlTool != null) {
            registry.register(generateHtmlTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("GenerateHtmlTool 未注入，跳过注册");
        }
        if (generateVisualizationTool != null) {
            registry.register(generateVisualizationTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("GenerateVisualizationTool 未注入，跳过注册");
        }
        if (generateThreeJsTool != null) {
            registry.register(generateThreeJsTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("GenerateThreeJsTool 未注入，跳过注册");
        }
        if (generateMindmapTool != null) {
            registry.register(generateMindmapTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("GenerateMindmapTool 未注入，跳过注册");
        }

        if (imageGenerationTool != null) {
            registry.register(imageGenerationTool, ToolGroup.ALWAYS_ON);
        } else {
            log.warn("ImageGenerationTool 未注入，跳过注册");
        }

        // ── 别名注册 ──
        registry.registerAlias("rag", "rag_retrieve");
        registry.registerAlias("rag_search", "rag_retrieve");
        registry.registerAlias("run_code", "code_execution");
        registry.registerAlias("code_run", "code_execution");

        log.info("=== Agent 工具注册完成，共 {} 个工具 ===", registry.listToolNames().size());
    }
}
