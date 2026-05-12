package com.learnthink.web.controller;

import com.learnthink.common.dto.chat.*;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
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
     * Start or resume a profile-building chat session for a course.
     */
    @PostMapping("/start")
    public Result<ChatStartResponse> startChat(@RequestBody ChatStartRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        System.out.println(">>> CHAT START: userId=" + userId + " courseId=" + request.getCourseId());
        log.info("Start chat: userId={}, courseId={}", userId, request.getCourseId());
        ChatStartResponse response = chatService.startChat(userId, request);
        return Result.success(response);
    }

    /**
     * Send a message in an existing chat session, get AI response (non-streaming fallback).
     */
    @PostMapping("/{chatId}/send")
    public Result<ChatSendResponse> sendMessage(@PathVariable String chatId,
                                                 @RequestBody ChatSendRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Send message: userId={}, chatId={}", userId, chatId);
        ChatSendResponse response = chatService.sendMessage(userId, chatId, request);
        return Result.success(response);
    }

    /**
     * Streaming send — AI response returned token-by-token via SSE.
     */
    @PostMapping(value = "/{chatId}/send/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSend(@PathVariable String chatId, @RequestBody ChatSendRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        System.out.println(">>> CHAT STREAM: userId=" + userId + " chatId=" + chatId + " content=" + request.getContent());
        log.info("Stream send: userId={}, chatId={}", userId, chatId);

        SseEmitter emitter = new SseEmitter(120000L);

        chatService.streamMessage(userId, chatId, request)
            .subscribe(
                chunk -> sendSse(emitter, chunk),
                error -> {
                    log.error("Stream error", error);
                    try {
                        String msg = error.getMessage() != null ? error.getMessage() : "stream error";
                        emitter.send(SseEmitter.event().name("error").data(msg));
                    } catch (IOException ignored) {
                        // ignore
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
     * Get all messages for a chat session.
     */
    @GetMapping("/{chatId}/messages")
    public Result<List<ChatMessageDto>> getMessages(@PathVariable String chatId) {
        String userId = UserContextUtil.getCurrentUserId();
        List<ChatMessageDto> messages = chatService.getMessages(userId, chatId);
        return Result.success(messages);
    }

    /**
     * List chat sessions for the current user in a course.
     */
    @GetMapping("/sessions")
    public Result<List<ChatSessionDto>> getSessions(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        List<ChatSessionDto> sessions = chatService.getSessions(userId, courseId);
        return Result.success(sessions);
    }

    /**
     * Delete a chat session.
     */
    @DeleteMapping("/{chatId}")
    public Result<Void> deleteSession(@PathVariable String chatId) {
        String userId = UserContextUtil.getCurrentUserId();
        chatService.deleteSession(userId, chatId);
        return Result.success(null, "会话已删除");
    }

    /**
     * Manually trigger profile analysis from chat history.
     */
    @PostMapping("/{chatId}/analyze")
    public Result<ProfileSummaryDto> analyzeProfile(@PathVariable String chatId) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Analyze profile: userId={}, chatId={}", userId, chatId);
        ProfileSummaryDto result = chatService.analyzeProfile(userId, chatId);
        return Result.success(result, "画像分析完成");
    }

    private void sendSse(SseEmitter emitter, String chunk) {
        try {
            if (chunk.startsWith("__sse:")) {
                // Internal event format: "__sse:<eventName>\n<data>"
                int nl = chunk.indexOf('\n');
                String eventName = chunk.substring(6, nl);
                String data = chunk.substring(nl + 1);
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } else {
                emitter.send(SseEmitter.event().name("chunk").data(chunk));
            }
        } catch (IOException e) {
            // Client disconnected — ignore
        }
    }
}
