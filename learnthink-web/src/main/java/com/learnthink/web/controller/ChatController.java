package com.learnthink.web.controller;

import com.learnthink.common.dto.chat.*;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 开始或恢复课程的画像构建对话会话。
     */
    @PostMapping("/start")
    public Result<ChatStartResponse> startChat(@RequestBody ChatStartRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Start chat: userId={}, courseId={}", userId, request.getCourseId());
        ChatStartResponse response = chatService.startChat(userId, request);
        return Result.success(response);
    }

    /**
     * 流式发送 — AI 响应通过 SSE 逐 token 返回。
     */
    @PostMapping(value = "/{chatId}/send/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSend(@PathVariable String chatId, @RequestBody ChatSendRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Stream send: userId={}, chatId={}", userId, chatId);

        SseEmitter emitter = new SseEmitter(120000L);

        // 减小 SSE 响应缓冲区，确保每个 token 立即刷新
        try {
            HttpServletResponse resp = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getResponse();
            if (resp != null) resp.setBufferSize(512);
        } catch (Exception ignored) {}

        chatService.streamMessage(userId, chatId, request)
            .subscribe(
                event -> sendSse(emitter, event),
                error -> {
                    log.error("Stream error", error);
                    try {
                        String msg = error.getMessage() != null ? error.getMessage() : "stream error";
                        emitter.send(SseEmitter.event().name("error").data(msg));
                    } catch (IOException ignored) {
                        // 忽略
                    } finally {
                        emitter.complete();
                    }
                },
                () -> {
                    log.info("Stream complete for chat {}", chatId);
                    emitter.complete();
                }
            );

        return emitter;
    }

    /**
     * 获取对话会话的所有消息。
     */
    @GetMapping("/{chatId}/messages")
    public Result<ChatMessagesResponse> getMessages(@PathVariable String chatId) {
        String userId = UserContextUtil.getCurrentUserId();
        ChatMessagesResponse response = chatService.getMessages(userId, chatId);
        return Result.success(response);
    }

    /**
     * 列出当前用户在课程中的对话会话。
     */
    @GetMapping("/sessions")
    public Result<List<ChatSessionDto>> getSessions(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        List<ChatSessionDto> sessions = chatService.getSessions(userId, courseId);
        return Result.success(sessions);
    }

    /**
     * 删除对话会话。
     */
    @DeleteMapping("/{chatId}")
    public Result<Void> deleteSession(@PathVariable String chatId) {
        String userId = UserContextUtil.getCurrentUserId();
        chatService.deleteSession(userId, chatId);
        return Result.success(null, "会话已删除");
    }

    /**
     * 手动触发从对话历史中分析画像。
     */
    @PostMapping("/{chatId}/analyze")
    public Result<ProfileSummaryDto> analyzeProfile(@PathVariable String chatId) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Analyze profile: userId={}, chatId={}", userId, chatId);
        ProfileSummaryDto result = chatService.analyzeProfile(userId, chatId);
        return Result.success(result, "画像分析完成");
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
