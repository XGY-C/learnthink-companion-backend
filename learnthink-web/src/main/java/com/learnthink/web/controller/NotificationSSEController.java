package com.learnthink.web.controller;

import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.NotificationSSEBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 通知 SSE 控制器 - 提供实时通知推送端点
 *
 * <p>前端通过 EventSource 连接 {@code /notifications/sse?token=xxx}，
 * 服务端在创建新通知时通过 SSE 事件推送到客户端。</p>
 */
@Slf4j
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationSSEController {

    private final NotificationSSEBroadcaster broadcaster;

    /**
     * 订阅通知 SSE 流
     *
     * @return SseEmitter
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        String userId = UserContextUtil.getCurrentUserId();
        if (userId == null) {
            log.warn("SSE subscription without valid user context");
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("Unauthorized"));
            return emitter;
        }
        log.info("SSE notification subscription: userId={}", userId);
        return broadcaster.subscribe(userId);
    }
}
