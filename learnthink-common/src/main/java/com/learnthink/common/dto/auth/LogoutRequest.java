package com.learnthink.common.dto.auth;

import lombok.Data;

/**
 * 登出请求
 */
@Data
public class LogoutRequest {
    private String refreshToken;
}
