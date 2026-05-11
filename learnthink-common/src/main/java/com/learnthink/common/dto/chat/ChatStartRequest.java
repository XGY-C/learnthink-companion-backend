package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatStartRequest {
    private String courseId;
    private boolean forceNew;
}
