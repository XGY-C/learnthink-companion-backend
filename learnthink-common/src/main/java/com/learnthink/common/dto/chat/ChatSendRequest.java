package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendRequest {
    private String content;
    /** Required for lazy session creation on first message. */
    private String courseId;
    /**
     * Chat mode: "chat" (default), "resource" (资源生成), "plan" (学习规划).
     * Controls which intent the system looks for in user messages.
     */
    private String mode;
}
