package com.learnthink.core.directanswer.domain.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 直接解答完整响应（历史回放）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectAnswerResponse(
    @JsonProperty(required = true) String sessionId,
    @JsonProperty(required = true) String mode,
    @JsonProperty(required = true) String source,
    @JsonProperty(required = true) String question,
    AnswerCard answerCard,
    ProblemAnalysis problemAnalysis,
    StrategyOverview strategyOverview,
    ReasoningChain reasoningChain,
    MethodSummary methodSummary,
    ErrorWarning errorWarning,
    List<KnowledgeCard> prerequisiteKnowledge,
    AnswerMetadata metadata,
    List<DirectAnswerSectionInfo> sections,
    String thoughtContent
) {}
