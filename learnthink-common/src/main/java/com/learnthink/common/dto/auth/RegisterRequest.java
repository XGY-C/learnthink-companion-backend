package com.learnthink.common.dto.auth;

import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {
    private String username;
    private String email;
    private String password;
    private String verificationCode; // 邮箱验证码
    private String courseId; // 可选
}
