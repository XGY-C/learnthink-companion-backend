package com.learnthink.common.dto.chat;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class ChatMessageDto {
    private String messageId;
    private String role;
    private String content;
    private String createdAt;
    private String mode;
    private String feedback;
    private Map<String, Object> metadataJson;
    private Object thinking;
    /** Plan offer metadata persisted alongside the AI reply message */
    private Object planOffer;
    /** ReAct thought steps from tutoring sessions (JSON string) */
    private String reactThoughts;
}
