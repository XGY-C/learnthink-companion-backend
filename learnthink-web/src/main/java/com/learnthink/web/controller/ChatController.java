package com.learnthink.web.controller;

import com.learnthink.common.dto.chat.*;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
        log.info("Start chat: userId={}, courseId={}", userId, request.getCourseId());
        ChatStartResponse response = chatService.startChat(userId, request);
        return Result.success(response);
    }

    /**
     * Send a message in an existing chat session, get AI response.
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
     * Manually trigger profile analysis from chat history.
     */
    @PostMapping("/{chatId}/analyze")
    public Result<ProfileSummaryDto> analyzeProfile(@PathVariable String chatId) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Analyze profile: userId={}, chatId={}", userId, chatId);
        ProfileSummaryDto result = chatService.analyzeProfile(userId, chatId);
        return Result.success(result, "画像分析完成");
    }
}
