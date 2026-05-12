package com.learnthink.common.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileChatRequest {
    private String courseId;
    private String message;
    private Map<String, String> context;
}
