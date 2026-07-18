package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionDto {
    private String chatId;
    private String courseId;
    private String type;
    private String title;
    private int messageCount;
    private String lastMessagePreview;
    private String lastMessageAt;
    private boolean analyzed;
    private String profileVersionId;
    private String createdAt;
}
