package com.learnthink.web.controller;

import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.directanswer.domain.request.DirectAnswerStartRequest;
import com.learnthink.core.directanswer.domain.response.DirectAnswerResponse;
import com.learnthink.core.directanswer.event.DirectAnswerEventEmitter;
import com.learnthink.core.directanswer.event.FlushableSseEmitter;
import com.learnthink.core.directanswer.service.DirectAnswerService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DirectAnswer Controller。
 * 端点：POST /direct-answer/create、POST /direct-answer/start(SSE)、GET /direct-answer/{sessionId}
 */
@RestController
@RequestMapping("/direct-answer")
public class DirectAnswerController {
    private static final Logger log = LoggerFactory.getLogger(DirectAnswerController.class);

    private final DirectAnswerService directAnswerService;

    public DirectAnswerController(DirectAnswerService directAnswerService) {
        this.directAnswerService = directAnswerService;
    }

    /**
     * 创建 DirectAnswer 会话。
     */
    @PostMapping("/create")
    public Result<Map<String, String>> createSession(@RequestBody DirectAnswerStartRequest request) {
        String sessionId = directAnswerService.createSession(request);
        return Result.success(Map.of("sessionId", sessionId));
    }

    /**
     * 启动直接解答（SSE 流式推送），10 分钟超时。
     * 使用 FlushableSseEmitter 确保每个 SSE 事件立即送达客户端，实现真正的流式输出。
     */
    @PostMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startAnswer(@RequestBody DirectAnswerStartRequest request,
                                  HttpServletResponse response) {
        FlushableSseEmitter emitter = new FlushableSseEmitter(10 * 60 * 1000L, response);
        emitter.onTimeout(() -> {
            log.warn("DirectAnswer SSE timeout: {}", request.sessionId());
            DirectAnswerEventEmitter de = new DirectAnswerEventEmitter(emitter);
            de.error("TIMEOUT", "生成超时，请重试", true);
            de.complete();
        });
        emitter.onError(throwable -> log.error("DirectAnswer SSE error", throwable));
        CompletableFuture.runAsync(() -> {
            directAnswerService.startAnswer(request, new DirectAnswerEventEmitter(emitter));
        });
        return emitter;
    }

    /**
     * 获取历史解答。
     */
    @GetMapping("/{sessionId}")
    public Result<DirectAnswerResponse> getAnswer(@PathVariable String sessionId) {
        DirectAnswerResponse answer = directAnswerService.getAnswer(sessionId);
        return Result.success(answer);
    }

    /**
     * 列出用户的所有解答会话。
     */
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> listSessions(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        List<Map<String, Object>> sessions = directAnswerService.listSessions(userId, page, size);
        return Result.success(sessions);
    }
}
