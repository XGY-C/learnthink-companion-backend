package com.learnthink.common.dto.auth;

import lombok.Data;

/**
 * 刷新Token请求
 */
@Data
public class RefreshTokenRequest {
    private String refreshToken;
}
