package com.learnthink.core.agent.orchestration;

import com.learnthink.core.agent.framework.AgentResult;
import com.learnthink.core.agent.framework.AgentContext;
import com.learnthink.core.agent.impl.*;
import com.learnthink.core.agent.impl.generators.*;
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

    @Bean
    public ResourceGenerationGraph.Publisher resourcePublisher() {
        return (taskId, resourceType, content, review, ctx) -> {
            // In production: persist content to MinIO/DB, create resource_item record
            log.info("Publishing resource: type={}, title={}, confidence={}",
                resourceType, content.title(), review != null ? review.confidence() : "none");

            // Store to object storage (placeholder)
            String contentRef = "resources/" + ctx.userId() + "/" + taskId + "/" + resourceType;

            // TODO: persist to resource_items table via ResourceService
            return AgentResult.of("published:" + contentRef);
        };
    }

    @Bean
    public ResourceGenerationGraph resourceGenerationGraph(
        ProfileAgent profileAgent,
        RetrieverAgent retrieverAgent,
        PlannerAgent plannerAgent,
        GeneratorAgent generatorAgent,
        ReviewerAgent reviewerAgent,
        ResourceGenerationGraph.Publisher publisher) {

        return new ResourceGenerationGraph(
            profileAgent, retrieverAgent, plannerAgent,
            generatorAgent, reviewerAgent, publisher);
    }
}
