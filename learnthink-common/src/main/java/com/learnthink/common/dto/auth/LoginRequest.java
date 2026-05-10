package com.learnthink.common.dto.auth;

import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginRequest {
    private String email; // 邮箱
    private String password;
}
