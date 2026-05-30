package com.learnthink.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!UserContextUtil.isLoggedIn()) {
            log.warn("Request without user context: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.getWriter().write(
                objectMapper.writeValueAsString(Map.of("code", 401, "message", "未登录或登录已过期"))
            );
            return false;
        }

        // 管理员路径需要 admin 角色
        if (request.getRequestURI().startsWith("/admin/")) {
            if (!"admin".equals(UserContextUtil.getCurrentUserRole())) {
                log.warn("Non-admin access to admin path: {} {}", request.getMethod(), request.getRequestURI());
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                response.getWriter().write(
                    objectMapper.writeValueAsString(Map.of("code", 403, "message", "权限不足，需要管理员权限"))
                );
                return false;
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        UserContextUtil.clear();
    }
}
