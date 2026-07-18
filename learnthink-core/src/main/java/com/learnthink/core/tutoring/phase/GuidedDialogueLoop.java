package com.learnthink.core.tutoring.phase;

import com.learnthink.common.dto.tutoring.GuidedAnswerRequest;
import com.learnthink.core.domain.entity.ChatMessage;
import com.learnthink.core.domain.entity.GuidedStepStateEntity;
import com.learnthink.core.domain.entity.TutoringSession;
import com.learnthink.core.repository.ChatMessageMapper;
import com.learnthink.core.service.chat.ChatMessageService;
import com.learnthink.core.tutoring.domain.*;
import com.learnthink.core.tutoring.event.TutoringEventEmitter;
import com.learnthink.core.tutoring.evaluate.GuidedAnswerEvaluator;
import com.learnthink.core.tutoring.repository.GuidedStepStateMapper;
import com.learnthink.core.tutoring.repository.TutoringSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GuidedDialogueLoop {
    private static final Logger log = LoggerFactory.getLogger(GuidedDialogueLoop.class);

    private static final Pattern GUIDANCE_PATTERN =
        Pattern.compile("<guidance>(.*?)</guidance>", Pattern.DOTALL);
    private static final Pattern QUESTION_PATTERN =
        Pattern.compile("<question>(.*?)</question>", Pattern.DOTALL);

    private final ChatClient chatClient;
    private final GeneratorPromptBuilder promptBuilder;
    private final GuidedAnswerEvaluator evaluator;
    private final GuidedStepStateMapper stepStateMapper;
    private final GuidedHistoryStore historyStore;
    private final TutoringSessionMapper sessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;

    public GuidedDialogueLoop(@Qualifier("generationChatClientBuilder") ChatClient.Builder builder,
                               GeneratorPromptBuilder promptBuilder,
                               GuidedAnswerEvaluator evaluator,
                               GuidedStepStateMapper stepStateMapper,
                               GuidedHistoryStore historyStore,
                               TutoringSessionMapper sessionMapper,
                               ChatMessageMapper chatMessageMapper,
                               ChatMessageService chatMessageService,
                               ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.promptBuilder = promptBuilder;
        this.evaluator = evaluator;
        this.stepStateMapper = stepStateMapper;
        this.historyStore = historyStore;
        this.sessionMapper = sessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行引导对话循环（首次调用）。
     * 推进到第一个"等待学生作答"点，然后挂起。
     */
    public void execute(ExecutionPlan plan, ResolvedResources resources,
                        TutoringContext context, TutoringEventEmitter emitter) {

        List<GuidedStep> steps = plan.guidedSteps();
        String sessionId = context.sessionId();

        // 加载或创建跨步骤历史
        GuidedDialogueHistory history = historyStore.load(sessionId);
        if (history == null) {
            history = new GuidedDialogueHistory(sessionId, context.question(), 0, List.of());
            historyStore.save(sessionId, history);  // 首次创建后立即持久化，避免 resumeGuided 时找不到
        }

        int startIdx = history.currentStepIndex();

        for (int i = startIdx; i < steps.size(); i++) {
            GuidedStep step = steps.get(i);

            // 1. 通知步骤开始
            emitter.guidedStepStart(step.id(), step.stage(), step.title(),
                i + 1, steps.size());

            // 2. 流式生成引导讲解 + 提问（调用点②）
            String[] guidanceAndQuestion = streamGuidance(
                step, resources, context, emitter, history, i + 1, steps.size());
            String guidance = guidanceAndQuestion[0];
            String question = guidanceAndQuestion[1];

            // 3. 持久化步骤状态，挂起等待学生作答
            GuidedStepStateEntity entity = new GuidedStepStateEntity();
            entity.setTutoringSessionId(sessionId);
            entity.setStepId(step.id());
            entity.setStepOrder(step.order());
            entity.setStage(step.stage());
            entity.setTitle(step.title());
            entity.setGuidanceContent(guidance);
            entity.setQuestion(question);
            entity.setAttemptCount(0);
            entity.setMaxAttempts(2);
            entity.setTimeSpentMs(0L);
            entity.setStatus("waiting_answer");
            entity.setCreatedAt(LocalDateTime.now());
            stepStateMapper.insert(entity);

            // 4. 发送提问事件
            emitter.guidedQuestion(step.id(), question);
            emitter.guidedWaitingAnswer(step.id(), 0, 2, false);

            log.info("Guided step {} suspended at session {}, waiting for answer", step.id(), sessionId);
            return; // 挂起，等待学生作答后调用 resumeGuided
        }

        // 所有步骤已完成（恢复场景）→ 直接总结
        streamSummaryAndDone(plan, context, history, emitter);
    }

    /**
     * 学生作答后恢复对话。
     */
    public void resumeGuided(GuidedAnswerRequest request, TutoringEventEmitter emitter) {
        String sessionId = request.sessionId();

        // 加载跨步骤历史
        GuidedDialogueHistory history = historyStore.load(sessionId);
        if (history == null) {
            emitter.error("GUIDED_HISTORY_NOT_FOUND",
                "引导对话历史不存在或已过期", "guided", false);
            emitter.complete();
            return;
        }

        // 加载当前等待中的步骤状态
        GuidedStepStateEntity entity = stepStateMapper.findWaitingBySession(sessionId);
        if (entity == null) {
            emitter.error("NO_WAITING_STEP",
                "没有等待作答的步骤", "guided", false);
            emitter.complete();
            return;
        }

        // 从 session 中恢复 ExecutionPlan 以获取 GuidedStep 定义
        ExecutionPlan plan = getPlanFromSession(sessionId);
        if (plan == null || !plan.isGuided()) {
            emitter.error("PLAN_NOT_FOUND",
                "无法加载引导计划", "guided", false);
            emitter.complete();
            return;
        }

        GuidedStep step = findStepById(plan.guidedSteps(), entity.getStepId());
        if (step == null) {
            emitter.error("STEP_NOT_FOUND",
                "步骤定义不存在: " + entity.getStepId(), "guided", false);
            emitter.complete();
            return;
        }

        GuidedStepState state = entityToState(entity);

        // 处理揭示答案请求
        if ("reveal".equals(request.action())) {
            handleReveal(sessionId, step, state, history, plan, emitter);
            return;
        }

        // 处理学生作答
        if (request.answer() == null || request.answer().isBlank()) {
            emitter.error("EMPTY_ANSWER", "作答内容不能为空", "guided", false);
            emitter.complete();
            return;
        }

        handleAnswer(sessionId, step, state, history, plan, request, emitter);
    }

    /**
     * 处理学生作答：评估 → 正确则推进 / 错误则给提示重试。
     */
    private void handleAnswer(String sessionId, GuidedStep step, GuidedStepState state,
                              GuidedDialogueHistory history, ExecutionPlan plan,
                              GuidedAnswerRequest request, TutoringEventEmitter emitter) {

        // 调用点③：评估学生作答
        EvaluationResult eval = evaluator.evaluate(
            step, state.question(), request.answer(), state, history);

        log.info("Guided step {} evaluation: correct={}, evaluation={}",
            step.id(), eval.correct(), eval.evaluation());

        if (eval.correct() || "partial".equals(eval.evaluation())) {
            // 正确或部分正确 → 推进
            long timeSpent = System.currentTimeMillis();

            // 更新步骤状态为完成
            updateStepDone(sessionId, step.id(), request.answer(),
                eval.feedback(), eval.evaluation(), timeSpent);

            // 调用点④：流式过渡反馈
            GuidedStep nextStep = findNextStep(plan.guidedSteps(), step);
            streamFeedback(step, request.answer(), eval.evaluation(), nextStep, history, emitter);

            emitter.guidedStepDone(step.id(), eval.evaluation(), timeSpent);

            // 写入历史
            GuidedDialogueHistory.StepTurn turn = new GuidedDialogueHistory.StepTurn(
                step.id(), step.stage(), step.title(),
                state.guidanceContent(), state.question(),
                request.answer(), eval.feedback(),
                eval.evaluation(), state.attemptCount() + 1, List.of());
            history = history.withNewTurn(turn);
            historyStore.save(sessionId, history);

            // 推进到下一步
            advanceToNextStep(sessionId, plan, step, history, emitter);

        } else {
            // 错误 → 给提示，允许重试
            int newAttempt = state.attemptCount() + 1;
            String hint = getHint(step, newAttempt);
            boolean allowReveal = newAttempt >= state.maxAttempts() || step.allowRevealAnswer();

            // 更新步骤状态
            GuidedStepStateEntity updateEntity = stepStateMapper.findWaitingBySession(sessionId);
            if (updateEntity != null) {
                updateEntity.setAttemptCount(newAttempt);
                updateEntity.setHint(hint);
                updateEntity.setStudentAnswer(request.answer());
                updateEntity.setFeedback(eval.feedback());
                stepStateMapper.updateById(updateEntity);
            }

            emitter.guidedFeedback(step.id(), eval.feedback(), hint,
                allowReveal, false);
            emitter.guidedWaitingAnswer(step.id(), newAttempt, state.maxAttempts(), allowReveal);
        }
    }

    /**
     * 处理揭示答案。
     */
    private void handleReveal(String sessionId, GuidedStep step, GuidedStepState state,
                              GuidedDialogueHistory history, ExecutionPlan plan,
                              TutoringEventEmitter emitter) {

        long timeSpent = System.currentTimeMillis();

        // 调用点⑤：流式生成答案解释
        String previousAttempts = state.studentAnswer() != null ? state.studentAnswer() : "无";
        String systemPrompt = promptBuilder.buildGuidedRevealedPrompt(
            step, state, previousAttempts, history);

        StringBuilder revealedContent = new StringBuilder();
        Flux<ChatResponse> stream = chatClient.prompt()
            .system(systemPrompt)
            .user("请给出答案并解释。")
            .stream()
            .chatResponse();

        stream.doOnNext(response -> {
            String chunk = extractContent(response);
            if (chunk != null && !chunk.isEmpty()) {
                revealedContent.append(chunk);
                emitter.guidedGuidanceChunk(step.id(), chunk);
            }
        }).blockLast();

        String revealed = revealedContent.toString();
        emitter.guidedRevealed(step.id(), step.expectedAnswer(), revealed);

        // 更新步骤状态
        updateStepDone(sessionId, step.id(), "[揭示答案] " + step.expectedAnswer(),
            revealed, "revealed", timeSpent);

        emitter.guidedStepDone(step.id(), "revealed", timeSpent);

        // 写入历史
        GuidedDialogueHistory.StepTurn turn = new GuidedDialogueHistory.StepTurn(
            step.id(), step.stage(), step.title(),
            state.guidanceContent(), state.question(),
            "[揭示答案]", revealed,
            "revealed", state.attemptCount() + 1, List.of());
        history = history.withNewTurn(turn);
        historyStore.save(sessionId, history);

        // 推进到下一步
        advanceToNextStep(sessionId, plan, step, history, emitter);
    }

    /**
     * 推进到下一步。
     */
    private void advanceToNextStep(String sessionId, ExecutionPlan plan, GuidedStep currentStep,
                                    GuidedDialogueHistory history, TutoringEventEmitter emitter) {
        List<GuidedStep> steps = plan.guidedSteps();
        int nextIdx = currentStep.order(); // order is 1-based, so nextIdx = order gives next index (0-based)

        if (nextIdx < steps.size()) {
            // 还有下一步
            GuidedStep nextStep = steps.get(nextIdx);

            // 需要恢复 resources 和 context
            TutoringSession session = sessionMapper.selectById(sessionId);
            ResolvedResources resources = new ResolvedResources(Map.of());
            TutoringContext context = new TutoringContext(
                session != null ? session.getUserId() : null,
                session != null ? session.getQuestion() : history.originalQuestion(),
                sessionId, null, null, null, null, null, null,
                session != null ? session.getSubMode() : "guided");

            // 执行下一步
            emitter.guidedStepStart(nextStep.id(), nextStep.stage(), nextStep.title(),
                nextIdx + 1, steps.size());

            String[] guidanceAndQuestion = streamGuidance(
                nextStep, resources, context, emitter, history, nextIdx + 1, steps.size());

            // 持久化步骤状态
            GuidedStepStateEntity entity = new GuidedStepStateEntity();
            entity.setTutoringSessionId(sessionId);
            entity.setStepId(nextStep.id());
            entity.setStepOrder(nextStep.order());
            entity.setStage(nextStep.stage());
            entity.setTitle(nextStep.title());
            entity.setGuidanceContent(guidanceAndQuestion[0]);
            entity.setQuestion(guidanceAndQuestion[1]);
            entity.setAttemptCount(0);
            entity.setMaxAttempts(2);
            entity.setTimeSpentMs(0L);
            entity.setStatus("waiting_answer");
            entity.setCreatedAt(LocalDateTime.now());
            stepStateMapper.insert(entity);

            emitter.guidedQuestion(nextStep.id(), guidanceAndQuestion[1]);
            emitter.guidedWaitingAnswer(nextStep.id(), 0, 2, false);

            log.info("Advanced to guided step {} at session {}", nextStep.id(), sessionId);
        } else {
            // 所有步骤完成 → 总结
            streamSummaryAndDone(plan, contextFromSession(sessionId, history), history, emitter);
        }
    }

    /**
     * 调用点②：流式生成引导讲解 + 提问。
     */
    private String[] streamGuidance(GuidedStep step, ResolvedResources resources,
                                     TutoringContext context, TutoringEventEmitter emitter,
                                     GuidedDialogueHistory history, int stepIndex, int totalSteps) {
        Map<String, Object> profile = context.profileSnapshot();

        String systemPrompt = promptBuilder.buildGuidedGuidancePrompt(
            step, resources, profile, context.question(), history, stepIndex, totalSteps);

        Flux<ChatResponse> stream = chatClient.prompt()
            .system(systemPrompt)
            .user("请生成引导讲解。")
            .stream()
            .chatResponse();

        StringBuilder content = new StringBuilder();
        stream.doOnNext(response -> {
            String chunk = extractContent(response);
            if (chunk != null && !chunk.isEmpty()) {
                content.append(chunk);
                // 不在此处流式发送，等解析完标签后再发送
            }
        }).blockLast();

        // 解析 <guidance> 和 <question> 标签
        String fullContent = content.toString();
        String guidance = extractTag(fullContent, GUIDANCE_PATTERN, fullContent);
        String question = extractTag(fullContent, QUESTION_PATTERN, "");

        // 如果没有匹配到标签，尝试按行分割（兜底）
        if (question.isEmpty() && guidance.equals(fullContent) && !fullContent.isBlank()) {
            // 尝试以最后一个问号分割
            int lastQuestion = fullContent.lastIndexOf('？');
            if (lastQuestion > 0) {
                question = fullContent.substring(lastQuestion + 1).trim();
                if (question.isEmpty()) {
                    question = fullContent.substring(
                        Math.max(fullContent.lastIndexOf('\n', lastQuestion) + 1, 0),
                        lastQuestion + 1).trim();
                }
            }
        }

        if (question.isEmpty()) {
            question = "请根据上述引导，思考并给出你的回答。";
        }

        guidance = guidance.trim();
        question = question.trim();

        // 解析完成后，一次性发送 guidance 内容
        emitter.guidedGuidanceChunk(step.id(), guidance);

        return new String[]{guidance, question};
    }

    /**
     * 调用点④：流式过渡反馈。
     */
    private void streamFeedback(GuidedStep step, String studentAnswer, String evaluation,
                                 GuidedStep nextStep, GuidedDialogueHistory history,
                                 TutoringEventEmitter emitter) {
        String systemPrompt = promptBuilder.buildGuidedFeedbackPrompt(
            step, studentAnswer, evaluation, nextStep, history);

        Flux<ChatResponse> stream = chatClient.prompt()
            .system(systemPrompt)
            .user("请生成过渡反馈。")
            .stream()
            .chatResponse();

        stream.doOnNext(response -> {
            String chunk = extractContent(response);
            if (chunk != null && !chunk.isEmpty()) {
                emitter.guidedGuidanceChunk(step.id(), chunk);
            }
        }).blockLast();
    }

    /**
     * 调用点⑥：流式总结 + done。
     */
    private void streamSummaryAndDone(ExecutionPlan plan, TutoringContext context,
                                       GuidedDialogueHistory history, TutoringEventEmitter emitter) {
        String systemPrompt = promptBuilder.buildGuidedSummaryPrompt(
            plan, context.question(), history, context.profileSnapshot());

        Flux<ChatResponse> stream = chatClient.prompt()
            .system(systemPrompt)
            .user("请生成解题回顾总结。")
            .stream()
            .chatResponse();

        StringBuilder summaryContent = new StringBuilder();
        stream.doOnNext(response -> {
            String chunk = extractContent(response);
            if (chunk != null && !chunk.isEmpty()) {
                summaryContent.append(chunk);
                emitter.guidedSummaryChunk(chunk);
            }
        }).blockLast();

        String summary = summaryContent.toString();

        // 更新 session 状态
        TutoringSession session = sessionMapper.selectById(context.sessionId());
        if (session != null) {
            session.setStatus("completed");
            session.setCompletedAt(LocalDateTime.now());
            session.setResultSummary(summary);
            sessionMapper.updateById(session);

            // 更新 chat_messages 中的 AI 占位消息为最终总结
            if (session.getChatId() != null && !session.getChatId().isBlank()) {
                updateGuidedChatMessage(session.getChatId(), context.sessionId(),
                    context.userId(), summary);
            }
        }

        emitter.done(context.sessionId());
        emitter.complete();
    }

    /**
     * 更新 chat_messages 中 guided 模式的 AI 占位消息为最终总结。
     */
    private void updateGuidedChatMessage(String chatId, String sessionId,
                                         String userId, String summary) {
        try {
            QueryWrapper<ChatMessage> wrapper = new QueryWrapper<>();
            wrapper.eq("session_id", chatId)
                   .eq("role", "assistant")
                   .like("metadata_json", sessionId)
                   .orderByDesc("seq_num")
                   .last("LIMIT 1");
            ChatMessage asstMsg = chatMessageMapper.selectOne(wrapper);
            if (asstMsg != null) {
                asstMsg.setContent(summary != null && !summary.isBlank()
                    ? summary : "(引导式教学已完成)");
                chatMessageMapper.updateById(asstMsg);
                log.info("Guided completion updated in chat_messages for chat {}", chatId);
            }
        } catch (Exception e) {
            log.warn("Failed to update guided chat message for chat {}: {}", chatId, e.getMessage());
        }
    }

    // ===== Helper Methods =====

    private ExecutionPlan getPlanFromSession(String sessionId) {
        try {
            TutoringSession session = sessionMapper.selectById(sessionId);
            if (session == null || session.getExecutionPlan() == null) return null;
            return objectMapper.readValue(session.getExecutionPlan(), ExecutionPlan.class);
        } catch (Exception e) {
            log.error("Failed to load ExecutionPlan for session {}", sessionId, e);
            return null;
        }
    }

    private GuidedStep findStepById(List<GuidedStep> steps, String stepId) {
        return steps.stream().filter(s -> s.id().equals(stepId)).findFirst().orElse(null);
    }

    private GuidedStep findNextStep(List<GuidedStep> steps, GuidedStep current) {
        int nextOrder = current.order() + 1;
        return steps.stream().filter(s -> s.order() == nextOrder).findFirst().orElse(null);
    }

    private String getHint(GuidedStep step, int attempt) {
        if (step.hintChain() == null || step.hintChain().isEmpty()) {
            return "再想想，注意题目中的关键信息。";
        }
        int idx = Math.min(attempt - 1, step.hintChain().size() - 1);
        return step.hintChain().get(idx);
    }

    private void updateStepDone(String sessionId, String stepId, String studentAnswer,
                                 String feedback, String evaluation, long timeSpentMs) {
        GuidedStepStateEntity entity = stepStateMapper.findWaitingBySession(sessionId);
        if (entity != null && entity.getStepId().equals(stepId)) {
            entity.setStudentAnswer(studentAnswer);
            entity.setFeedback(feedback);
            entity.setEvaluation(evaluation);
            entity.setTimeSpentMs(timeSpentMs);
            entity.setStatus("done");
            stepStateMapper.updateById(entity);
        }
    }

    private GuidedStepState entityToState(GuidedStepStateEntity entity) {
        return new GuidedStepState(
            entity.getStepId(),
            entity.getStepOrder(),
            entity.getStage(),
            entity.getTitle(),
            entity.getGuidanceContent(),
            entity.getQuestion(),
            entity.getStudentAnswer(),
            entity.getFeedback(),
            entity.getHint(),
            entity.getEvaluation(),
            entity.getAttemptCount() != null ? entity.getAttemptCount() : 0,
            entity.getMaxAttempts() != null ? entity.getMaxAttempts() : 2,
            entity.getTimeSpentMs() != null ? entity.getTimeSpentMs() : 0L,
            entity.getStatus()
        );
    }

    private TutoringContext contextFromSession(String sessionId, GuidedDialogueHistory history) {
        TutoringSession session = sessionMapper.selectById(sessionId);
        return new TutoringContext(
            session != null ? session.getUserId() : null,
            history != null ? history.originalQuestion() : (session != null ? session.getQuestion() : ""),
            sessionId, null, null, null, null, null, null,
            session != null ? session.getSubMode() : "guided");
    }

    private String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null
            || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private String extractTag(String text, Pattern pattern, String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }
}
