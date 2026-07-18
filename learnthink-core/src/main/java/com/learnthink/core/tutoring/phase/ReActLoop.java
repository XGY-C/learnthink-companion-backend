package com.learnthink.core.tutoring.phase;

import com.learnthink.core.config.TutoringConfig;
import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;

@Component
public class ReActLoop {
    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private final ChatClient chatClient;
    private final ReActPromptBuilder promptBuilder;
    private final ReActParser parser;
    private final ReactStateStore reactStateStore;
    private final TutoringConfig config;

    public ReActLoop(@Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder,
                     ReActPromptBuilder promptBuilder, ReActParser parser,
                     ReactStateStore reactStateStore, TutoringConfig config) {
        this.chatClient = chatClientBuilder.build();
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.reactStateStore = reactStateStore;
        this.config = config;
    }

    public ExecutionPlan execute(TutoringContext context, TutoringEventEmitter emitter) {
        int maxIterations = config.getReact().getMaxIterations();
        List<Map<String, Object>> conversationHistory = new ArrayList<>();
        String accumulatedQuestion = context.question();
        String originalQuestion = context.question();

        // Restore from saved state if continuing
        if (context.reactState() != null) {
            conversationHistory = new ArrayList<>(context.reactState().conversationHistory());
            accumulatedQuestion = context.reactState().accumulatedQuestion();
            originalQuestion = context.reactState().originalQuestion();
        }

        // Add student clarification response to history if present
        if (context.clarificationResponse() != null) {
            Map<String, Object> studentEntry = new HashMap<>();
            studentEntry.put("role", "student");
            Map<String, Object> response = new HashMap<>();
            response.put("skipped", context.clarificationResponse().skipped());
            response.put("selectedOptionId", context.clarificationResponse().selectedOptionId());
            response.put("freeInput", context.clarificationResponse().freeInput());
            studentEntry.put("response", response);
            conversationHistory.add(studentEntry);

            // Update accumulated question based on student response
            if (!context.clarificationResponse().skipped() &&
                context.clarificationResponse().freeInput() != null &&
                !context.clarificationResponse().freeInput().isBlank()) {
                accumulatedQuestion = context.clarificationResponse().freeInput();
            }
        }

        int iteration = context.reactState() != null ? context.reactState().iteration() : 0;
        String accumulatedText = "";
        boolean retried = false;

        while (iteration < maxIterations) {
            iteration++;
            log.info("ReAct iteration {}/{} for question: {}", iteration, maxIterations,
                accumulatedQuestion.substring(0, Math.min(50, accumulatedQuestion.length())));

            try {
                ReactState currentState = new ReactState(
                    context.sessionId() != null ? context.sessionId() : UUID.randomUUID().toString(),
                    iteration, conversationHistory, accumulatedQuestion, originalQuestion);

                List<Message> messages = promptBuilder.buildMessages(
                    new TutoringContext(context.userId(), accumulatedQuestion, context.sessionId(),
                        context.profileSnapshot(), context.pathPosition(),
                        context.recentLearning(), context.recentTutoring(), currentState, null, context.subMode()),
                    null);

                // Streaming call with accumulated text
                StringBuilder responseBuilder = new StringBuilder();
                Flux<String> stream = chatClient.prompt()
                    .messages(messages)
                    .stream()
                    .content();

                String finalQuestion = accumulatedQuestion;
                stream.doOnNext(chunk -> responseBuilder.append(chunk))
                    .blockLast(Duration.ofMillis(config.getReact().getPerIterationTimeoutMs()));

                accumulatedText = responseBuilder.toString();
                ReActParser.ReActTurn turn = parser.parse(accumulatedText);

                log.info("ReAct iteration {}: action={}, thought={}",
                    iteration, turn.action(), turn.thought().substring(0, Math.min(80, turn.thought().length())));

                // 通过 SSE 发送思考过程给前端
                emitter.reactThought(iteration, turn.thought(), turn.action());

                // Save architect entry to history
                Map<String, Object> architectEntry = new HashMap<>();
                architectEntry.put("role", "architect");
                architectEntry.put("iteration", iteration);
                architectEntry.put("thought", turn.thought());
                architectEntry.put("action", turn.action());
                conversationHistory.add(architectEntry);

                if ("output_plan".equals(turn.action())) {
                    String jsonBlock = parser.extractJsonBlock(accumulatedText);
                    if (jsonBlock != null) {
                        ExecutionPlan plan = parser.parsePlan(jsonBlock);
                        reactStateStore.delete(context.sessionId());
                        return plan;
                    }
                    // JSON parsing failed, force output
                    log.warn("output_plan action but no valid JSON found, forcing plan");
                    return forcePlan(accumulatedText, "JSON parsing failed, best-effort plan");
                }

                if ("ask_clarification".equals(turn.action())) {
                    String jsonBlock = parser.extractJsonBlock(accumulatedText);
                    if (jsonBlock != null) {
                        try {
                            ExecutionPlan partialPlan = parser.parsePlan(jsonBlock);
                            ClarificationDecision decision = partialPlan.clarificationDecision();
                            Clarification clarification = partialPlan.clarification();

                            // Send clarify events
                            emitter.planMode("clarify");
                            emitter.planClarify(
                                context.sessionId() != null ? context.sessionId() : "",
                                decision, clarification);

                            // Save ReAct state to Redis
                            ReactState savedState = new ReactState(
                                context.sessionId() != null ? context.sessionId() : "",
                                iteration, conversationHistory, accumulatedQuestion, originalQuestion);
                            reactStateStore.save(context.sessionId(), savedState);

                            return ExecutionPlan.forClarify(
                                partialPlan.planId(), decision, clarification);
                        } catch (Exception e) {
                            log.warn("Failed to parse clarification JSON: {}", e.getMessage());
                        }
                    }
                }

                // If action is neither clear, continue loop
                log.info("ReAct continuing: action={}", turn.action());

            } catch (Exception e) {
                log.error("ReAct iteration {} failed: {}", iteration, e.getMessage());
                // 真正的 retry-once：第一次异常时回退本轮迭代号并重试一次；
                // 第二次仍失败则直接 forcePlan 兜底，避免连续多轮无效 LLM 调用。
                if (!retried) {
                    retried = true;
                    iteration--;
                    log.warn("ReAct will retry once after failure");
                    continue;
                }
                return forcePlan(accumulatedText,
                    "ReAct 连续失败，基于现有最佳理解制定计划：" + e.getMessage());
            }
        }

        return forcePlan(accumulatedText,
            "达到最大轮次（" + maxIterations + "），基于现有最佳理解制定计划");
    }

    private ExecutionPlan forcePlan(String accumulatedText, String reason) {
        String jsonBlock = parser.extractJsonBlock(accumulatedText);
        if (jsonBlock != null) {
            try {
                ExecutionPlan plan = parser.parsePlan(jsonBlock);
                if (plan.isAnswer()) return plan;
            } catch (Exception e) {
                log.warn("Force plan parsing failed: {}", e.getMessage());
            }
        }

        log.warn("Forced plan with reason: {}", reason);
        ClarificationDecision decision = new ClarificationDecision("proceed", reason);
        return ExecutionPlan.forAnswer(
            UUID.randomUUID().toString(), decision,
            new QuestionAnalysis("conceptual", "", "", "intermediate",
                List.of(), List.of(), ""),
            "基于现有信息制定的教学计划", null, List.of(), List.of(), null);
    }
}
