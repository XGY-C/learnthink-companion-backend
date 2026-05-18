package com.learnthink.common.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息响应(脱敏——不含密码)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {
    private String id;
    private String username;
    private String email;
    private String role;
    private String displayName;
    private String avatarUrl;
    private String bio;
    private String major;
    private String grade;
    private String createdAt;
}
