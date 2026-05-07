package com.learnthink.core.service;

import com.learnthink.common.dto.auth.*;

/**
 * 认证服务接口
 */
public interface AuthService {
    
    /**
     * 用户登录
     * @param request 登录请求
     * @param clientIp 客户端IP（用于限流）
     * @return 登录响应
     */
    LoginResponse login(LoginRequest request, String clientIp);
    
    /**
     * 用户注册
     * @param request 注册请求
     * @return 登录响应（注册成功后自动登录）
     */
    LoginResponse register(RegisterRequest request);
    
    /**
     * 刷新Token
     * @param request 刷新Token请求
     * @return 新的Token对
     */
    RefreshTokenResponse refreshToken(RefreshTokenRequest request);
    
    /**
     * 登出
     * @param accessToken Access Token
     * @param request 登出请求（包含refreshToken）
     */
    void logout(String accessToken, LogoutRequest request);
}
