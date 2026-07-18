package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.runtime.AgentResult;
import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.impl.*;
import com.learnthink.core.agent.impl.generators.*;
import com.learnthink.core.agent.manager.AgentManager;
import com.learnthink.core.agent.tools.visual.SmartSvgTool;
import com.learnthink.core.agent.tools.visual.GenerateSvgChartTool;
import com.learnthink.core.agent.tools.visual.GenerateChartTool;
import com.learnthink.core.agent.tools.visual.GenerateMermaidTool;
import com.learnthink.core.agent.tools.visual.GenerateHtmlTool;
import com.learnthink.core.agent.tools.visual.GenerateVisualizationTool;
import com.learnthink.core.agent.tools.visual.GenerateThreeJsTool;
import com.learnthink.core.agent.tools.visual.GenerateMindmapTool;
import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.config.LearnThinkProperties;
import com.learnthink.core.config.PromptLoader;
import com.learnthink.core.repository.BookInfoMapper;
import com.learnthink.core.repository.KnowledgeDocumentMapper;
import com.learnthink.core.service.PushService;
import com.learnthink.core.service.TaskPersistenceService;
import com.learnthink.core.service.VideoRenderPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 多智能体框架的 Spring 配置。
 * 将图流水线、Agent 实现和发布器连接在一起。
 */
@Configuration
public class AgentRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeConfig.class);

    /**
     * RagTool——跨 Agent 共享的知识库检索工具。
     * 注册为 Spring Bean，以便任何 Agent 可以通过构造函数注入接收它。
     */
    @Bean
    public RagTool ragTool(EvidenceRetriever.RagClient ragClient) {
        return new RagTool(ragClient);
    }

    @Bean
    public BookInfoTool bookInfoTool(KnowledgeDocumentMapper docMapper, BookInfoMapper bookInfoMapper) {
        return new BookInfoTool(docMapper, bookInfoMapper);
    }

    @Bean
    public ImageGenerationTool imageGenerationTool(
            @Qualifier("sparkRestTemplate") RestTemplate sparkRestTemplate,
            LearnThinkProperties props) {
        var spark = props.getSpark();
        var qwen = props.getQwenImage();
        return new ImageGenerationTool(
            sparkRestTemplate, props.getImageProvider(),
            spark.getAppId(), spark.getApiKey(), spark.getApiSecret(),
            spark.getDomain(), spark.getBaseUrl(),
            qwen.getApiKey(), qwen.getModel(), qwen.getBaseUrl(),
            qwen.isPromptExtend(), qwen.isWatermark());
    }

    @Bean
    public SvgGenerationTool svgGenerationTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new SvgGenerationTool(builder);
    }

    // ── Smart v2 可视化工具（三阶段流水线）──

    @Bean
    public SmartSvgTool smartSvgTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new SmartSvgTool(builder);
    }

    @Bean
    public GenerateChartTool generateChartTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new GenerateChartTool(builder);
    }

    @Bean
    public GenerateMermaidTool generateMermaidTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new GenerateMermaidTool(builder);
    }

    @Bean
    public GenerateSvgChartTool generateSvgChartTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new GenerateSvgChartTool(builder);
    }

    @Bean
    public GenerateHtmlTool generateHtmlTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new GenerateHtmlTool(builder);
    }

    @Bean
    public GenerateVisualizationTool generateVisualizationTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new GenerateVisualizationTool(builder);
    }

    @Bean
    public GenerateThreeJsTool generateThreeJsTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder,
            PromptLoader promptLoader) {
        return new GenerateThreeJsTool(builder, promptLoader);
    }

    @Bean
    public GenerateMindmapTool generateMindmapTool(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        return new GenerateMindmapTool(builder);
    }

    @Bean
    public AgentManager agentManager(
            DocumentGenerator docGen, ExerciseGenerator quizGen,
            ReadingGenerator readGen, CodeGenerator codeGen, MindmapGenerator mapGen,
            HtmlDocumentGenerator htmlGen) {
        AgentManager mgr = new AgentManager();
        mgr.register("doc", docGen, 3);
        mgr.register("quiz", quizGen, 2);
        mgr.register("reading", readGen, 2);
        mgr.register("code", codeGen, 2);
        mgr.register("mindmap", mapGen, 2);
        mgr.register("html", htmlGen, 2);
        return mgr;
    }

    @Bean
    public IllustrationService illustrationService(
            @Qualifier("generationChatClientBuilder") ChatClient.Builder builder,
            SmartSvgTool smartSvgTool,
            GenerateSvgChartTool generateSvgChartTool,
            GenerateMermaidTool generateMermaidTool,
            ImageGenerationTool imageGenerationTool,
            AliOSSUtil aliOSSUtil,
            PromptLoader promptLoader) {
        return new IllustrationService(
            builder.build(),
            smartSvgTool, generateSvgChartTool, generateMermaidTool,
            imageGenerationTool, aliOSSUtil, promptLoader);
    }

    @Bean
    public ResourceGenerationGraph.Publisher resourcePublisher() {
        return (taskId, resourceType, content, review, ctx) -> {
            log.info("Publishing resource: type={}, title={}, confidence={}",
                resourceType, content.title(), review != null ? review.confidence() : "none");

            String contentRef = "resources/" + ctx.userId() + "/" + taskId + "/" + resourceType;
            return AgentResult.of("published:" + contentRef);
        };
    }

    /**
     * 资源生成图——主要的多 Agent 流水线。
     *
     * 架构说明（来自设计文档 §3.2）：
     * 系统包含两个概念图：
     *   A. 对话画像图：ConversationAgent ⇄ 用户 → ProfileAnalyzer（交互式）
     *      - ConversationAgent 由 ChatServiceImpl 直接调用，用于交互式对话
     *      - 可注册为 GraphNode，用于非交互式的回放/调试场景
     *   B. 资源生成图（本 Bean）：Profile → Retrieve → Plan → Generate → Review
     *      - 在画像足够且用户确认生成时触发
     *
     * ConversationAgent 使用 Plan-Act-Observe-Reflect 循环。
     * 它属于 Agent 系统的一部分——出于交互式对话的实用原因被直接调用。
     */
    @Bean
    public ResourceGenerationGraph resourceGenerationGraph(
        ProfileAnalyzer profileAnalyzer,
        EvidenceRetriever evidenceRetriever,
        CurriculumPlanner curriculumPlanner,
        ResourceGenerator resourceGenerator,
        ContentReviewer contentReviewer,
        ResourceGenerationGraph.Publisher publisher,
        TaskPersistenceService persistenceService,
        AgentManager agentManager,
        VideoRenderPoller videoRenderPoller,
        IllustrationService illustrationService,
        PushService pushService) {

        return new ResourceGenerationGraph(
            profileAnalyzer, evidenceRetriever, curriculumPlanner,
            resourceGenerator, contentReviewer, publisher, persistenceService,
            agentManager, videoRenderPoller, illustrationService, pushService);
    }
}
