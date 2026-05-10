package com.learnthink.core.service;

import com.learnthink.common.dto.chat.*;

import java.util.List;

public interface ChatService {

    ChatStartResponse startChat(String userId, ChatStartRequest request);

    ChatSendResponse sendMessage(String userId, String chatId, ChatSendRequest request);

    List<ChatMessageDto> getMessages(String userId, String chatId);

    List<ChatSessionDto> getSessions(String userId, String courseId);

    ProfileSummaryDto analyzeProfile(String userId, String chatId);
}
