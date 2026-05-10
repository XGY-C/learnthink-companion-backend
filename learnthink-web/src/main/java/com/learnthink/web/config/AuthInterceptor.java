package com.learnthink.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.TokenStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

/**
 * 认证拦截器
 * 用于从请求头中提取 Access Token，验证后设置用户上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenStorageService tokenStorageService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取 Authorization 头
        String authorization = request.getHeader("Authorization");
        
        // 如果没有 Authorization 头，直接放行（由 Spring Security 处理未认证情况）
        if (!StringUtils.hasText(authorization)) {
            return true;
        }

        // 检查是否是 Bearer Token
        if (!authorization.startsWith("Bearer ")) {
            log.warn("无效的 Authorization 格式: {}", authorization);
            return true; // 让 Spring Security 处理
        }

        String accessToken = authorization.substring(7); // 去掉 "Bearer " 前缀

        try {
            // 从 Redis 中获取 Token 信息
            Map<String, Object> tokenInfo = tokenStorageService.getAccessTokenInfo(accessToken);
            
            if (tokenInfo == null) {
                log.warn("Access Token 无效或已过期: {}", accessToken);
                sendUnauthorizedResponse(response, ErrorCode.AUTH_ERROR, "Token 无效或已过期");
                return false;
            }

            // 提取用户信息
            String userId = (String) tokenInfo.get("userId");
            String role = (String) tokenInfo.get("role");
            
            if (userId == null) {
                log.error("Token 信息中缺少 userId");
                sendUnauthorizedResponse(response, ErrorCode.AUTH_ERROR, "Token 信息不完整");
                return false;
            }

            // 设置用户上下文
            UserContextUtil.setCurrentUser(new UserContextUtil.UserInfo(userId, null, role));
            log.debug("用户上下文已设置: userId={}, role={}", userId, role);

            return true;
        } catch (Exception e) {
            log.error("验证 Access Token 时发生错误", e);
            sendUnauthorizedResponse(response, ErrorCode.AUTH_ERROR, "Token 验证失败");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求完成后清除用户上下文，防止内存泄漏
        UserContextUtil.clear();
        log.debug("用户上下文已清除");
    }

    /**
     * 发送未授权响应
     */
    private void sendUnauthorizedResponse(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        
        Result<Void> result = Result.error(errorCode.getCode(), message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
