package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendResponse {
    private String chatId;
    private List<ChatMessageDto> newMessages;
    private boolean profileReady;
    private String profileVersionId;

    /** v3.1: whether the system should offer resource generation to the user */
    private boolean generationReady;
    /** v3.1: generation-related metadata (covered dimensions, suggested resource types) */
    private Map<String, Object> generationMeta;
}
