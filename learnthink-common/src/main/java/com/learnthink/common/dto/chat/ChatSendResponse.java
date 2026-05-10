package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendResponse {
    private String chatId;
    private List<ChatMessageDto> newMessages;
    private boolean profileReady;
    private String profileVersionId;
}
