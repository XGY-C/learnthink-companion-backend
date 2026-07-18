package com.learnthink.common.dto.chat;

import lombok.Data;

@Data
public class FeedbackRequest {
    /** "like" | "dislike" | null（取消） */
    private String feedback;
}
