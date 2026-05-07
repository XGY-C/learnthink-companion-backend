package com.learnthink.web.controller;

import com.learnthink.common.dto.auth.*;
import com.learnthink.common.result.Result;
import com.learnthink.core.service.AuthService;
import com.learnthink.core.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    
    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    
    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request,
                                       @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
                                       @RequestHeader(value = "X-Real-IP", required = false) String realIp) {
        // 获取客户端IP
        String clientIp = getClientIp(forwardedFor, realIp);
        log.info("Login attempt from IP: {}, username: {}", clientIp, request.getUsername());
        
        LoginResponse response = authService.login(request, clientIp);
        return Result.success(response);
    }
    
    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<LoginResponse> register(@RequestBody RegisterRequest request) {
        log.info("Register attempt for username: {}", request.getUsername());
        
        LoginResponse response = authService.register(request);
        return Result.success(response);
    }
    
    /**
     * 发送邮箱验证码
     */
    @PostMapping("/send-verification-code")
    public Result<Map<String, Object>> sendVerificationCode(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        if (email == null || email.isEmpty()) {
            return Result.error("邮箱不能为空");
        }
        
        log.info("Send verification code to email: {}", email);
        
        // 发送验证码（生产环境不返回验证码）
        String code = emailVerificationService.sendVerificationCode(email);
        
        // 返回成功响应
        Map<String, Object> data = new HashMap<>();
        data.put("message", "验证码已发送到您的邮箱");
        // 注意：生产环境不应该返回验证码，这里仅用于测试
        // data.put("code", code);
        
        return Result.success(data);
    }
    
    /**
     * 刷新Token
     */
    @PostMapping("/refresh")
    public Result<RefreshTokenResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        log.info("Token refresh attempt");
        
        RefreshTokenResponse response = authService.refreshToken(request);
        return Result.success(response);
    }
    
    /**
     * 登出
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authorization,
                               @RequestBody(required = false) LogoutRequest request) {
        // 提取Access Token
        String accessToken = extractToken(authorization);
        if (accessToken == null) {
            return Result.error("无效的Token");
        }
        
        log.info("Logout attempt");
        authService.logout(accessToken, request);
        return Result.success(null, "已登出");
    }
    
    /**
     * 从Authorization header中提取Token
     */
    private String extractToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return null;
    }
    
    /**
     * 获取客户端IP
     */
    private String getClientIp(String forwardedFor, String realIp) {
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // X-Forwarded-For可能包含多个IP，取第一个
            return forwardedFor.split(",")[0].trim();
        }
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return "unknown";
    }
}
