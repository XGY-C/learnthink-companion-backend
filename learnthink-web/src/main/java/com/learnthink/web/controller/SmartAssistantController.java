package com.learnthink.web.controller;

import com.learnthink.common.dto.chat.SmartAssistantRequest;
import com.learnthink.common.dto.chat.SseEvent;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.SmartAssistantService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 智能助手控制类
 * @author 谢光益
 * @since 2026/5/26
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/smart-assistant")
public class SmartAssistantController {

    private final SmartAssistantService smartAssistantService;

    /**
     * 智能助手回答问题（流式返回，包含进度状态）
     * @param request 请求体（question + 可选 courseId）
     * @return SSE 流式响应
     */
    @PostMapping(value = "/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answer(@RequestBody SmartAssistantRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("智能助手回答问题：{}", request.getQuestion());

        SseEmitter emitter = new SseEmitter(120000L);

        try {
            HttpServletResponse resp = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getResponse();
            if (resp != null) resp.setBufferSize(512);
        } catch (Exception ignored) {
        }

        smartAssistantService.answer(request, userId)
            .subscribe(
                event -> sendSse(emitter, event),
                error -> {
                    log.error("Stream error", error);
                    try {
                        String msg = error.getMessage() != null ? error.getMessage() : "stream error";
                        emitter.send(SseEmitter.event().name("error").data(msg));
                    } catch (IOException ignored) {
                    } finally {
                        emitter.complete();
                    }
                },
                () -> {
                    log.info("Stream complete for user {}", userId);
                    emitter.complete();
                }
            );

        return emitter;
    }

    private void sendSse(SseEmitter emitter, SseEvent event) {
        try {
            if (event.isNamed()) {
                emitter.send(SseEmitter.event().name(event.getEventName()).data(event.getData()));
            } else {
                emitter.send(SseEmitter.event().name("chunk").data(event.getData()));
            }
        } catch (IOException e) {
            // 客户端断连，忽略
        }
    }
}
