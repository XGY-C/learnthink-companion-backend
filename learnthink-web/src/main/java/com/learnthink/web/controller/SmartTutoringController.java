package com.learnthink.web.controller;

import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.directanswer.event.FlushableSseEmitter;
import com.learnthink.core.smart.domain.SmartAnswerRequest;
import com.learnthink.core.smart.domain.SmartStartRequest;
import com.learnthink.core.smart.event.SmartEventEmitter;
import com.learnthink.core.smart.service.SmartAgentLoop;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * Smart v2 智能辅导 Controller。
 * <p>端点：POST /smart/start（启动）、POST /smart/answer（学生回答）</p>
 */
@RestController
@RequestMapping("/smart")
public class SmartTutoringController {
    private static final Logger log = LoggerFactory.getLogger(SmartTutoringController.class);

    private final SmartAgentLoop smartAgentLoop;

    public SmartTutoringController(SmartAgentLoop smartAgentLoop) {
        this.smartAgentLoop = smartAgentLoop;
    }

    /**
     * 启动智能辅导（SSE 流式推送）。
     */
    @PostMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter start(@RequestBody SmartStartRequest request,
                            HttpServletResponse response) {
        FlushableSseEmitter emitter = new FlushableSseEmitter(10 * 60 * 1000L, response);
        emitter.onTimeout(() -> {
            log.warn("Smart SSE timeout: {}", request.sessionId());
            SmartEventEmitter se = new SmartEventEmitter(emitter);
            se.error("TIMEOUT", "智能辅导超时，请重试", true);
        });
        emitter.onError(throwable -> log.error("Smart SSE error", throwable));
        UserContextUtil.UserInfo userInfo = UserContextUtil.getCurrentUser();
        if (userInfo == null || userInfo.getUserId() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "未登录，请先登录后再使用智能辅导");
        }
        CompletableFuture.runAsync(() -> {
            UserContextUtil.setCurrentUser(userInfo);
            try {
                smartAgentLoop.execute(request, emitter);
            } finally {
                UserContextUtil.clear();
            }
        });
        return emitter;
    }

    /**
     * 学生回答（SSE 流式推送）。
     */
    @PostMapping(value = "/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answer(@RequestBody SmartAnswerRequest request,
                             HttpServletResponse response) {
        log.info("[SMART-DIAG] === /answer endpoint hit === sessionId={}, chatId={}, answerLen={}",
            request.sessionId(), request.chatId(),
            request.answer() != null ? request.answer().length() : 0);
        FlushableSseEmitter emitter = new FlushableSseEmitter(10 * 60 * 1000L, response);
        emitter.onTimeout(() -> {
            log.warn("Smart SSE timeout: {}", request.sessionId());
            SmartEventEmitter se = new SmartEventEmitter(emitter);
            se.error("TIMEOUT", "回复超时，请重试", true);
        });
        emitter.onError(throwable -> log.error("Smart SSE error", throwable));
        UserContextUtil.UserInfo userInfo = UserContextUtil.getCurrentUser();
        if (userInfo == null || userInfo.getUserId() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED.value(), "未登录，请先登录后再使用智能辅导");
        }
        CompletableFuture.runAsync(() -> {
            UserContextUtil.setCurrentUser(userInfo);
            try {
                smartAgentLoop.resume(request, emitter);
            } finally {
                UserContextUtil.clear();
            }
        });
        return emitter;
    }
}
