package com.learnthink.core.tutoring.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionPlan(
    @JsonProperty(required = true) String planId,
    @JsonProperty(required = true) String timestamp,
    @JsonProperty(required = true) String mode,

    @JsonProperty(required = true) ClarificationDecision clarificationDecision,

    Clarification clarification,
    QuestionAnalysis questionAnalysis,
    String teachingThesis,
    TeachingPersonalization personalization,
    List<ResourceRequirement> resourceRequirements,
    List<SectionBlueprint> sectionBlueprints,
    QualitySpec qualitySpec,
    List<GuidedStep> guidedSteps
) {
    public static ExecutionPlan forClarify(String planId, ClarificationDecision decision, Clarification clarification) {
        return new ExecutionPlan(planId, Instant.now().toString(), "clarify",
            decision, clarification, null, null, null, null, null, null, null);
    }

    public static ExecutionPlan forAnswer(String planId, ClarificationDecision decision,
                                           QuestionAnalysis questionAnalysis, String teachingThesis,
                                           TeachingPersonalization personalization,
                                           List<ResourceRequirement> resourceRequirements,
                                           List<SectionBlueprint> sectionBlueprints,
                                           QualitySpec qualitySpec) {
        return new ExecutionPlan(planId, Instant.now().toString(), "answer",
            decision, null, questionAnalysis, teachingThesis, personalization,
            resourceRequirements, sectionBlueprints, qualitySpec, null);
    }

    public static ExecutionPlan forGuided(String planId, ClarificationDecision decision,
                                           QuestionAnalysis questionAnalysis, String teachingThesis,
                                           TeachingPersonalization personalization,
                                           List<ResourceRequirement> resourceRequirements,
                                           List<GuidedStep> guidedSteps,
                                           QualitySpec qualitySpec) {
        return new ExecutionPlan(planId, Instant.now().toString(), "answer",
            decision, null, questionAnalysis, teachingThesis, personalization,
            resourceRequirements, null, qualitySpec, guidedSteps);
    }

    public boolean isClarify() { return "clarify".equals(mode); }
    public boolean isAnswer()  { return "answer".equals(mode); }
    public boolean isGuided() { return guidedSteps != null && !guidedSteps.isEmpty(); }

    public int diagramCount() {
        if (sectionBlueprints == null) return 0;
        return (int) sectionBlueprints.stream().filter(s -> s.expectedDiagram() != null).count();
    }
}
