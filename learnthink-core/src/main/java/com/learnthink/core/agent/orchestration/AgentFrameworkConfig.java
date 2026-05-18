package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.impl.*;
import com.learnthink.core.agent.impl.generators.*;
import com.learnthink.core.service.TaskPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the multi-agent framework.
 * Wires together the graph pipeline, agent implementations, and the publisher.
 */
@Configuration
public class AgentFrameworkConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentFrameworkConfig.class);

    /**
     * RagTool — shared knowledge base retrieval across agents.
     * Registered as a Spring bean so any Agent can receive it via constructor injection.
     */
    @Bean
    public RagTool ragTool(EvidenceRetriever.RagClient ragClient) {
        return new RagTool(ragClient);
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
     * Resource generation graph — the main multi-agent pipeline.
     *
     * Architecture note (from design doc §3.2):
     * The system has TWO conceptual graphs:
     *   A. Conversation profile graph: ConversationAgent ⇄ user → ProfileAnalyzer (interactive)
     *      - ConversationAgent is called directly by ChatServiceImpl for interactive chat
     *      - Can be registered as a GraphNode for non-interactive replay/debug scenarios
     *   B. Resource generation graph (this bean): Profile → Retrieve → Plan → Generate → Review
     *      - Triggered when profile is sufficient and user confirms generation
     *
     * ConversationAgent implements Agent<I,O> + Plan-Act-Observe-Reflect loop.
     * It IS part of the Agent system — just called directly for pragmatic interactive-chat reasons.
     */
    @Bean
    public ResourceGenerationGraph resourceGenerationGraph(
        ProfileAnalyzer profileAnalyzer,
        EvidenceRetriever evidenceRetriever,
        CurriculumPlanner curriculumPlanner,
        ResourceGenerator resourceGenerator,
        ContentReviewer contentReviewer,
        ResourceGenerationGraph.Publisher publisher,
        TaskPersistenceService persistenceService) {

        return new ResourceGenerationGraph(
            profileAnalyzer, evidenceRetriever, curriculumPlanner,
            resourceGenerator, contentReviewer, publisher, persistenceService);
    }
}
