package com.learnthink.common.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminTeacherResponse {
    private String id;
    private String username;
    private String email;
    private String displayName;
    private String avatarUrl;
    private String phone;
    private String status;
    private int courseCount;
    private List<String> courseNames;
    private String createdAt;
}
