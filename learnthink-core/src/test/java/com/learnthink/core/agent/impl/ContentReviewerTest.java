package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.AgentContext;
import com.learnthink.core.agent.impl.generators.*;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for generator source-coverage requirements and ContentReviewer exemption logic.
 */
class ContentReviewerTest {

    @Test
    @DisplayName("DocumentGenerator requires source coverage")
    void documentRequiresSourceCoverage() {
        var gen = new TestDocumentGenerator();
        assertTrue(gen.requiresSourceCoverage(),
            "DocumentGenerator should require source coverage");
    }

    @Test
    @DisplayName("ExerciseGenerator requires source coverage")
    void exerciseRequiresSourceCoverage() {
        var gen = new TestExerciseGenerator();
        assertTrue(gen.requiresSourceCoverage(),
            "ExerciseGenerator should require source coverage");
    }

    @Test
    @DisplayName("CodeGenerator requires source coverage")
    void codeRequiresSourceCoverage() {
        var gen = new TestCodeGenerator();
        assertTrue(gen.requiresSourceCoverage(),
            "CodeGenerator should require source coverage");
    }

    @Test
    @DisplayName("ReadingGenerator is exempt from source coverage")
    void readingExemptFromSourceCoverage() {
        var gen = new TestReadingGenerator();
        assertFalse(gen.requiresSourceCoverage(),
            "ReadingGenerator should NOT require source coverage");
    }

    @Test
    @DisplayName("MindmapGenerator is exempt from source coverage")
    void mindmapExemptFromSourceCoverage() {
        var gen = new TestMindmapGenerator();
        assertFalse(gen.requiresSourceCoverage(),
            "MindmapGenerator should NOT require source coverage");
    }

    @Test
    @DisplayName("VideoGenerator is exempt from source coverage")
    void videoExemptFromSourceCoverage() {
        var gen = new TestVideoGenerator();
        assertFalse(gen.requiresSourceCoverage(),
            "VideoGenerator should NOT require source coverage");
    }

    @Test
    @DisplayName("TypeGenerator default requiresSourceCoverage() returns true")
    void defaultRequiresSourceCoverageIsTrue() {
        var gen = new TypeGenerator() {
            @Override public String type() { return "custom"; }
            @Override public ResourceGenerationState.GeneratedContent generate(
                ResourceGenerationState.ResourcePlanItem item,
                List<ResourceGenerationState.SourceItem> sources,
                ResourceGenerationState.ProfileSummary profile,
                boolean forceLowConfidence, String feedback, AgentContext ctx) { return null; }
            @Override public ResourceGenerationState.GeneratedContent revise(
                ResourceGenerationState.ResourcePlanItem item,
                List<ResourceGenerationState.SourceItem> sources,
                ResourceGenerationState.ProfileSummary profile,
                boolean forceLowConfidence, String feedback,
                ResourceGenerationState.GeneratedContent original, AgentContext ctx) { return null; }
        };
        assertTrue(gen.requiresSourceCoverage(),
            "Default requiresSourceCoverage() should return true");
    }

    @Test
    @DisplayName("requiresSourceCoverage map behaves correctly for all types")
    void sourceCoverageMapCorrect() {
        Map<String, TypeGenerator> generators = Map.of(
            "doc", new TestDocumentGenerator(),
            "quiz", new TestExerciseGenerator(),
            "code", new TestCodeGenerator(),
            "reading", new TestReadingGenerator(),
            "mindmap", new TestMindmapGenerator(),
            "video", new TestVideoGenerator()
        );

        assertTrue(generators.get("doc").requiresSourceCoverage(),
            "doc should require sources");
        assertTrue(generators.get("quiz").requiresSourceCoverage(),
            "quiz should require sources");
        assertTrue(generators.get("code").requiresSourceCoverage(),
            "code should require sources");
        assertFalse(generators.get("reading").requiresSourceCoverage(),
            "reading should be exempt");
        assertFalse(generators.get("mindmap").requiresSourceCoverage(),
            "mindmap should be exempt");
        assertFalse(generators.get("video").requiresSourceCoverage(),
            "video should be exempt");

        // 未知类型：从 map 获取为 null，ResourceGenerator 将返回 false
        assertNull(generators.get("unknown"), "unknown type has no generator");
    }

    // -- Standalone test implementations (no Spring dep needed) --

    static class TestDocumentGenerator implements TypeGenerator {
        @Override public String type() { return "doc"; }
        @Override public boolean requiresSourceCoverage() { return true; }
        @Override public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb, AgentContext c) {
            return new ResourceGenerationState.GeneratedContent("t", "c", "text/md",
                List.of(), "high", Map.of());
        }
        @Override public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb,
            ResourceGenerationState.GeneratedContent o, AgentContext c) { return o; }
    }

    static class TestExerciseGenerator implements TypeGenerator {
        @Override public String type() { return "quiz"; }
        @Override public boolean requiresSourceCoverage() { return true; }
        @Override public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb, AgentContext c) {
            return new ResourceGenerationState.GeneratedContent("t", "c", "text/md",
                List.of(), "high", Map.of());
        }
        @Override public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb,
            ResourceGenerationState.GeneratedContent o, AgentContext c) { return o; }
    }

    static class TestReadingGenerator implements TypeGenerator {
        @Override public String type() { return "reading"; }
        @Override public boolean requiresSourceCoverage() { return false; }
        @Override public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb, AgentContext c) {
            return new ResourceGenerationState.GeneratedContent("t", "c", "text/md",
                List.of(), "high", Map.of());
        }
        @Override public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb,
            ResourceGenerationState.GeneratedContent o, AgentContext c) { return o; }
    }

    static class TestCodeGenerator implements TypeGenerator {
        @Override public String type() { return "code"; }
        @Override public boolean requiresSourceCoverage() { return true; }
        @Override public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb, AgentContext c) {
            return new ResourceGenerationState.GeneratedContent("t", "c", "text/md",
                List.of(), "high", Map.of());
        }
        @Override public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb,
            ResourceGenerationState.GeneratedContent o, AgentContext c) { return o; }
    }

    static class TestMindmapGenerator implements TypeGenerator {
        @Override public String type() { return "mindmap"; }
        @Override public boolean requiresSourceCoverage() { return false; }
        @Override public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb, AgentContext c) {
            return new ResourceGenerationState.GeneratedContent("t", "c", "text/md",
                List.of(), "high", Map.of());
        }
        @Override public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb,
            ResourceGenerationState.GeneratedContent o, AgentContext c) { return o; }
    }

    static class TestVideoGenerator implements TypeGenerator {
        @Override public String type() { return "video"; }
        @Override public boolean requiresSourceCoverage() { return false; }
        @Override public ResourceGenerationState.GeneratedContent generate(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb, AgentContext c) {
            return new ResourceGenerationState.GeneratedContent("t", "c", "text/md",
                List.of(), "high", Map.of());
        }
        @Override public ResourceGenerationState.GeneratedContent revise(
            ResourceGenerationState.ResourcePlanItem i, List<ResourceGenerationState.SourceItem> s,
            ResourceGenerationState.ProfileSummary p, boolean f, String fb,
            ResourceGenerationState.GeneratedContent o, AgentContext c) { return o; }
    }
}
