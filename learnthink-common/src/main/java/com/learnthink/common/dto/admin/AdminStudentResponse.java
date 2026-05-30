package com.learnthink.common.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminStudentResponse {
    private String id;
    private String username;
    private String email;
    private String displayName;
    private String avatarUrl;
    private String major;
    private String grade;
    private String role;
    private String status;
    private int courseCount;
    private int totalLearningMinutes;
    private String lastActiveAt;
    private String createdAt;
}
