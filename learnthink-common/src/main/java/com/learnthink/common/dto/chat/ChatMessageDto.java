package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {
    private String role;
    private String content;
    private String at;
    private Object thinking;
    /** Plan offer metadata persisted alongside the AI reply message */
    private Object planOffer;
}
