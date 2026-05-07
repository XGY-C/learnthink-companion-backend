package com.learnthink.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    SUCCESS(200, "成功"),
    PARAM_ERROR(400, "参数错误"),
    VALIDATION_ERROR(422, "验证失败"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    BUSINESS_ERROR(600, "业务错误"),
    
    // 认证相关错误码
    AUTH_ERROR("AUTH_401", "认证失败"),
    AUTH_RATE_LIMIT("AUTH_429", "登录尝试过于频繁"),
    TOKEN_EXPIRED("TOKEN_EXPIRED", "Token已过期");

    private final Object code;
    private final String message;
}
