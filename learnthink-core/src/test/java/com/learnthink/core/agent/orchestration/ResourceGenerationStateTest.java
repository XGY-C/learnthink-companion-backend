package com.learnthink.core.agent.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceGenerationState immutable records and sub-topic iteration.
 */
class ResourceGenerationStateTest {

    @Test
    @DisplayName("Constructor initializes task identity and default values")
    void constructorInitializesDefaults() {
        var state = new ResourceGenerationState(
            "task-1", "user-1", "course-1", "二叉树",
            List.of("doc", "quiz", "mindmap"), 2);

        assertEquals("task-1", state.taskId);
        assertEquals("user-1", state.userId);
        assertEquals("course-1", state.courseId);
        assertEquals("二叉树", state.topic);
        assertEquals(List.of("doc", "quiz", "mindmap"), state.resourceTypes);
        assertEquals(2, state.profileVersion);
        assertEquals("PENDING", state.status);
        assertNotNull(state.createdAt);
    }

    @Test
    @DisplayName("Default resource types when null passed")
    void defaultResourceTypes() {
        var state = new ResourceGenerationState(
            "t", "u", "c", "topic", null, 1);
        assertEquals(List.of("doc", "quiz", "reading", "code", "mindmap", "video"),
            state.resourceTypes);
    }

    @Test
    @DisplayName("RetrievalData.empty() creates empty instance")
    void retrievalDataEmpty() {
        var r = ResourceGenerationState.RetrievalData.empty();
        assertTrue(r.mergedSources().isEmpty());
        assertTrue(r.evidenceByType().isEmpty());
        assertFalse(r.forceLowConfidence());
        assertEquals(0, r.totalSources());
    }

    @Test
    @DisplayName("PlanningData.withResourcePlan() updates plan")
    void planningDataWithPlan() {
        var plan = new ResourceGenerationState.ResourcePlan(
            "# Outline", List.of(), List.of(), List.of("Reason"), List.of());
        var p = ResourceGenerationState.PlanningData.empty().withResourcePlan(plan);
        assertNotNull(p.resourcePlan());
        assertEquals(0, p.planRetryCount());
    }

    @Test
    @DisplayName("PlanningData.incrementRetry() bumps retry count")
    void planningDataIncrementRetry() {
        var p = ResourceGenerationState.PlanningData.empty().incrementRetry();
        assertEquals(1, p.planRetryCount());
    }

    @Test
    @DisplayName("GenerationData.withArtifact() adds artifact immutably")
    void generationDataWithArtifact() {
        var g = ResourceGenerationState.GenerationData.empty();
        var content = new ResourceGenerationState.GeneratedContent(
            "title", "body", "text/md", List.of(), "high", Map.of());
        var g2 = g.withArtifact("doc", content);
        assertTrue(g.artifacts().isEmpty(), "Original should be unchanged");
        assertEquals(1, g2.artifacts().size());
        assertEquals(content, g2.artifacts().get("doc"));
    }

    @Test
    @DisplayName("GenerationData.withFailedType() immutably tracks failures")
    void generationDataWithFailedType() {
        var g = ResourceGenerationState.GenerationData.empty();
        var g2 = g.withFailedType("video");
        assertTrue(g.failedTypes().isEmpty());
        assertTrue(g2.failedTypes().contains("video"));
    }

    @Test
    @DisplayName("ReviewData.withResult() stores and increments retry")
    void reviewDataWithResult() {
        var review = ResourceGenerationState.ReviewData.empty();
        var result = new ResourceGenerationState.ReviewResult(
            ResourceGenerationState.ReviewStatus.APPROVED,
            "high", "OK", List.of(), 0.85,
            ResourceGenerationState.ReviewAction.PUBLISH);
        var r2 = review.withResult("doc", result).incrementRetry();
        assertEquals(1, r2.reviewResults().size());
        assertEquals(1, r2.reviewRetryCount());
        assertEquals(ResourceGenerationState.ReviewAction.PUBLISH,
            r2.reviewResults().get("doc").action());
    }

    @Test
    @DisplayName("PublishingData.withPackId() and withPublishedType() compose correctly")
    void publishingDataComposition() {
        var p = ResourceGenerationState.PublishingData.empty();
        var p2 = p.withPackId("pack-1").withPublishedType("doc");
        assertEquals("pack-1", p2.packId());
        assertTrue(p2.publishedTypes().contains("doc"));
    }

    @Test
    @DisplayName("SubTopicProgress iterates correctly through sub-topics")
    void subTopicProgressIteration() {
        var progress = ResourceGenerationState.SubTopicProgress.empty().start(3);

        assertEquals(0, progress.currentIndex());
        assertEquals(3, progress.totalCount());
        assertTrue(progress.hasMore());
        assertFalse(progress.allDone());

        // Advance to sub-topic 1
        var p2 = progress.advance();
        assertEquals(1, p2.currentIndex());
        assertTrue(p2.hasMore());
        assertTrue(p2.completedIndices().contains(0));

        // Advance to sub-topic 2
        var p3 = p2.advance();
        assertEquals(2, p3.currentIndex());
        assertTrue(p3.completedIndices().contains(0));
        assertTrue(p3.completedIndices().contains(1));

        // Advance past last
        var p4 = p3.advance();
        assertEquals(3, p4.currentIndex());
        assertFalse(p4.hasMore());
        assertTrue(p4.allDone());
    }

    @Test
    @DisplayName("FeedbackContext.forRegenerate() sets regenerate state")
    void feedbackContextForRegenerate() {
        var feedback = ResourceGenerationState.FeedbackContext.empty()
            .forRegenerate(Set.of("doc", "quiz"), Map.of("doc", "Fix source", "quiz", "Too hard"));
        assertEquals(Set.of("doc", "quiz"), feedback.regenerateTypes());
        assertEquals("Fix source", feedback.reviewFeedbackByType().get("doc"));
        assertNull(feedback.planFeedback());
    }

    @Test
    @DisplayName("FeedbackContext.forReplan() sets plan feedback")
    void feedbackContextForReplan() {
        var feedback = ResourceGenerationState.FeedbackContext.empty()
            .forReplan("Restructure outline");
        assertEquals("Restructure outline", feedback.planFeedback());
        assertNull(feedback.regenerateTypes());
    }

    @Test
    @DisplayName("SubTopic record holds correct data")
    void subTopicRecord() {
        var st = new ResourceGenerationState.SubTopic(
            1, "Advanced", "Deep topics", List.of("kp1", "kp2"), 30, "4");
        assertEquals(1, st.index());
        assertEquals("Advanced", st.title());
        assertEquals("Deep topics", st.description());
        assertEquals(2, st.focusKeyPoints().size());
        assertEquals(30, st.estimatedMinutes());
        assertEquals("4", st.difficulty());
    }
}
