package com.learnthink.web.controller;

import com.learnthink.common.dto.practice.*;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.directanswer.event.FlushableSseEmitter;
import com.learnthink.core.service.PracticeService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PracticeController {

    private final PracticeService practiceService;

    @PostMapping("/practice/sessions")
    public Result<PracticeSessionDTO> createSession(@RequestBody CreatePracticeSessionRequest req) {
        return Result.success(practiceService.createSession(UserContextUtil.getCurrentUserId(), req));
    }

    @PostMapping("/practice/sessions/ai-generate")
    public Result<PracticeSessionDTO> aiGenerate(@RequestBody AiGenerateSessionRequest req) {
        return Result.success(practiceService.aiGenerateSession(UserContextUtil.getCurrentUserId(), req));
    }

    @GetMapping("/practice/sessions")
    public Result<?> listSessions(
            @RequestParam String courseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(practiceService.listSessions(UserContextUtil.getCurrentUserId(), courseId, page, size));
    }

    @GetMapping("/practice/stats")
    public Result<PracticeStatsDTO> getStats(@RequestParam String courseId) {
        return Result.success(practiceService.getStats(UserContextUtil.getCurrentUserId(), courseId));
    }

    @GetMapping("/practice/sessions/{id}")
    public Result<PracticeSessionDTO> getSession(@PathVariable String id) {
        return Result.success(practiceService.getSession(id, UserContextUtil.getCurrentUserId()));
    }

    @PostMapping("/practice/sessions/{sessionId}/items/{itemId}/answer")
    public Result<Void> recordItemAnswer(@PathVariable String sessionId, @PathVariable String itemId,
                                          @RequestBody RecordItemAnswerRequest req) {
        practiceService.recordItemAnswer(sessionId, itemId, UserContextUtil.getCurrentUserId(), req);
        return Result.success();
    }

    @PutMapping("/practice/sessions/{id}/complete")
    public Result<Void> complete(@PathVariable String id) {
        practiceService.completeSession(id, UserContextUtil.getCurrentUserId());
        return Result.success();
    }

    @GetMapping(value = "/practice/sessions/{id}/evaluation", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter evaluate(@PathVariable String id, HttpServletResponse response) {
        FlushableSseEmitter emitter = new FlushableSseEmitter(10 * 60 * 1000L, response);
        emitter.onTimeout(() -> log.warn("Practice evaluation SSE timeout: sessionId={}", id));
        emitter.onError(e -> log.error("Practice evaluation SSE error: sessionId={}", id, e));

        UserContextUtil.UserInfo userInfo = UserContextUtil.getCurrentUser();
        if (userInfo == null || userInfo.getUserId() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "未登录，请先登录");
        }
        String userId = userInfo.getUserId();
        CompletableFuture.runAsync(() -> {
            try {
                practiceService.evaluateSession(id, userId, emitter);
            } catch (Exception e) {
                log.error("Practice evaluation failed: sessionId={}", id, e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("评估失败"));
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });
        return emitter;
    }
}
