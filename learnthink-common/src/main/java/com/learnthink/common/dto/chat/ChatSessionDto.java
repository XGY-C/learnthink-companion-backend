package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionDto {
    private String chatId;
    private String courseId;
    private String title;
    private int messageCount;
    private String lastMessagePreview;
    private String lastMessageAt;
    private boolean analyzed;
    private String profileVersionId;
    private LocalDateTime createdAt;
}
