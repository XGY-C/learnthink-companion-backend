package com.learnthink.core.tutoring.phase;

import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.tutoring.context.ContextLoader;
import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class Phase1Architect {
    private static final Logger log = LoggerFactory.getLogger(Phase1Architect.class);

    private final ContextLoader contextLoader;
    private final ReActLoop reactLoop;
    private final ReactStateStore reactStateStore;

    public Phase1Architect(ContextLoader contextLoader, ReActLoop reactLoop,
                           ReactStateStore reactStateStore) {
        this.contextLoader = contextLoader;
        this.reactLoop = reactLoop;
        this.reactStateStore = reactStateStore;
    }

    public ExecutionPlan execute(TutoringContext requestContext, TutoringEventEmitter emitter) {
        String userId = UserContextUtil.getCurrentUserId();

        // Load context
        TutoringContext context = contextLoader.load(userId);
        context = new TutoringContext(userId, requestContext.question(),
            requestContext.sessionId(), context.profileSnapshot(), context.pathPosition(),
            context.recentLearning(), context.recentTutoring(),
            requestContext.reactState(), requestContext.clarificationResponse());

        // Determine if new session or continuation
        if (context.sessionId() != null && !context.sessionId().isBlank()) {
            ReactState savedState = reactStateStore.load(context.sessionId());
            if (savedState != null) {
                context = context.withReactState(savedState)
                    .withClarificationResponse(context.clarificationResponse());
                log.info("Continuing ReAct for session {} at iteration {}",
                    context.sessionId(), savedState.iteration());
            }
        }

        if (context.sessionId() == null || context.sessionId().isBlank()) {
            String sessionId = UUID.randomUUID().toString();
            context = new TutoringContext(context.userId(), context.question(), sessionId,
                context.profileSnapshot(), context.pathPosition(),
                context.recentLearning(), context.recentTutoring(),
                context.reactState(), context.clarificationResponse());
        }

        // Emit started
        emitter.started(context.sessionId());

        // Execute ReAct loop
        ExecutionPlan plan = reactLoop.execute(context, emitter);

        // Send plan events based on mode
        if (plan.isClarify()) {
            emitter.waitingClarification(context.sessionId());
            return plan;
        }

        // Send answer-mode events
        emitter.planMode("answer");
        if (plan.questionAnalysis() != null) {
            emitter.planAnalysis(plan.questionAnalysis());
        }
        if (plan.personalization() != null) {
            emitter.planPersonalization(plan.personalization());
        }
        if (plan.sectionBlueprints() != null) {
            emitter.planStructure(plan.sectionBlueprints());
        }
        if (plan.resourceRequirements() != null) {
            emitter.planResources(plan.resourceRequirements().size());
        }
        emitter.planDone(plan.planId(), plan.teachingThesis(),
            plan.sectionBlueprints() != null ? plan.sectionBlueprints().size() : 0,
            plan.resourceRequirements() != null ? plan.resourceRequirements().size() : 0,
            plan.diagramCount());

        return plan;
    }
}
