package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatStartResponse {
    private String chatId;
    private String courseId;
    private List<ChatMessageDto> messages;
    private boolean profileReady;
    private String profileVersionId;
}
