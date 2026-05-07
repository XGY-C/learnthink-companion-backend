package com.learnthink.core.service.impl;

import com.learnthink.common.dto.auth.*;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.common.util.PasswordValidator;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.service.AuthService;
import com.learnthink.core.service.EmailVerificationService;
import com.learnthink.core.service.TokenStorageService;
import com.learnthink.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;

/**
 * 认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    
    private final UserService userService;
    private final TokenStorageService tokenStorageService;
    private final EmailVerificationService emailVerificationService;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    
    // 登录限流Key前缀
    private static final String RATE_LIMIT_PREFIX = "rate_limit:login:";
    // 限流阈值：5次/分钟
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 5;
    private static final Duration RATE_LIMIT_TTL = Duration.ofMinutes(1);
    
    @Override
    public LoginResponse login(LoginRequest request, String clientIp) {
        // 1. 检查登录频率限制
        checkRateLimit(clientIp);
        
        // 2. 查找用户
        User user = userService.findByUsername(request.getUsername());
        if (user == null) {
            incrementRateLimit(clientIp);
            throw new BusinessException(ErrorCode.AUTH_ERROR, "用户名或密码错误");
        }
        
        // 3. 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            incrementRateLimit(clientIp);
            throw new BusinessException(ErrorCode.AUTH_ERROR, "用户名或密码错误");
        }
        
        // 4. 清除限流计数
        clearRateLimit(clientIp);
        
        // 5. 生成Token
        return generateTokens(user);
    }
    
    @Override
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        // 1. 验证邮箱格式
        if (request.getEmail() == null || request.getEmail().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮箱不能为空");
        }
        
        // 2. 验证邮箱验证码
        if (request.getVerificationCode() == null || request.getVerificationCode().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请输入邮箱验证码");
        }
        
        boolean isCodeValid = emailVerificationService.verifyCode(request.getEmail(), request.getVerificationCode());
        if (!isCodeValid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "邮箱验证码错误或已过期");
        }
        
        // 3. 验证密码强度
        PasswordValidator.validate(request.getPassword());
        
        // 4. 检查用户名是否已存在
        User existingUser = userService.findByUsername(request.getUsername());
        if (existingUser != null) {
            throw new BusinessException(ErrorCode.AUTH_ERROR, "用户名已存在");
        }
        
        // 5. 检查邮箱是否已注册
        User existingEmail = userService.findByEmail(request.getEmail());
        if (existingEmail != null) {
            throw new BusinessException(ErrorCode.AUTH_ERROR, "该邮箱已被注册");
        }
        
        // 6. 创建新用户
        User newUser = new User();
        newUser.setUsername(request.getUsername());
        newUser.setEmail(request.getEmail());
        newUser.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        newUser.setRole("student"); // 默认角色
        
        User savedUser = userService.save(newUser);
        
        log.info("User registered: {} with email: {}", savedUser.getUsername(), savedUser.getEmail());
        
        // 7. 自动生成Token（注册成功后自动登录）
        return generateTokens(savedUser);
    }
    
    @Override
    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        
        // 1. 验证Refresh Token
        Map<String, Object> rtInfo = tokenStorageService.getRefreshTokenInfo(refreshToken);
        if (rtInfo == null) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh Token已过期，请重新登录");
        }
        
        String userId = (String) rtInfo.get("userId");
        Integer storedTokenVer = (Integer) rtInfo.get("tokenVer");
        
        // 2. 检查Token版本号
        Integer currentTokenVer = tokenStorageService.getTokenVersion(userId);
        if (storedTokenVer == null || !storedTokenVer.equals(currentTokenVer)) {
            throw new BusinessException(ErrorCode.AUTH_ERROR, "Token已被撤销，请重新登录");
        }
        
        // 3. 删除旧Refresh Token（Token轮换）
        tokenStorageService.deleteRefreshToken(refreshToken, userId);
        
        // 4. 获取用户信息
        User user = userService.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_ERROR, "用户不存在");
        }
        
        // 5. 生成新Token对
        String newAccessToken = tokenStorageService.generateToken(32);
        String newRefreshToken = tokenStorageService.generateToken(48);
        
        tokenStorageService.storeAccessToken(newAccessToken, user.getId(), user.getRole(), currentTokenVer);
        tokenStorageService.storeRefreshToken(newRefreshToken, user.getId(), currentTokenVer);
        tokenStorageService.addRefreshTokenToUserIndex(user.getId(), newRefreshToken);
        
        log.info("Token refreshed for user: {}", userId);
        
        return RefreshTokenResponse.builder()
            .accessToken(newAccessToken)
            .refreshToken(newRefreshToken)
            .expiresIn(900)
            .build();
    }
    
    @Override
    public void logout(String accessToken, LogoutRequest request) {
        // 1. 获取Access Token信息
        Map<String, Object> atInfo = tokenStorageService.getAccessTokenInfo(accessToken);
        if (atInfo != null) {
            String userId = (String) atInfo.get("userId");
            
            // 2. 删除Access Token
            tokenStorageService.deleteAccessToken(accessToken);
            
            // 3. 删除Refresh Token
            if (request != null && request.getRefreshToken() != null) {
                tokenStorageService.deleteRefreshToken(request.getRefreshToken(), userId);
            }
            
            log.info("User logged out: {}", userId);
        }
    }
    
    /**
     * 生成Token对
     */
    private LoginResponse generateTokens(User user) {
        // 获取当前Token版本号
        Integer tokenVer = tokenStorageService.getTokenVersion(user.getId());
        
        // 生成不透明Token
        String accessToken = tokenStorageService.generateToken(32);
        String refreshToken = tokenStorageService.generateToken(48);
        
        // 存储到Redis
        tokenStorageService.storeAccessToken(accessToken, user.getId(), user.getRole(), tokenVer);
        tokenStorageService.storeRefreshToken(refreshToken, user.getId(), tokenVer);
        tokenStorageService.addRefreshTokenToUserIndex(user.getId(), refreshToken);
        
        // 构建响应
        UserInfoResponse userInfo = UserInfoResponse.builder()
            .id(user.getId())
            .username(user.getUsername())
            .role(user.getRole())
            .build();
        
        return LoginResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .expiresIn(900) // 15分钟
            .user(userInfo)
            .build();
    }
    
    /**
     * 检查登录频率限制
     */
    private void checkRateLimit(String clientIp) {
        String key = RATE_LIMIT_PREFIX + clientIp;
        String count = redisTemplate.opsForValue().get(key);
        if (count != null && Integer.parseInt(count) >= RATE_LIMIT_MAX_ATTEMPTS) {
            throw new BusinessException(ErrorCode.AUTH_RATE_LIMIT, "登录尝试过于频繁，请稍后再试");
        }
    }
    
    /**
     * 增加限流计数
     */
    private void incrementRateLimit(String clientIp) {
        String key = RATE_LIMIT_PREFIX + clientIp;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 第一次设置过期时间
            redisTemplate.expire(key, RATE_LIMIT_TTL);
        }
    }
    
    /**
     * 清除限流计数
     */
    private void clearRateLimit(String clientIp) {
        String key = RATE_LIMIT_PREFIX + clientIp;
        redisTemplate.delete(key);
    }
}
