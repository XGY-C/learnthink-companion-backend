package com.learnthink.web.controller;

import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户上下文测试控制器
 * 用于演示如何使用 UserContextUtil 获取当前用户信息
 */
@Slf4j
@RestController
@RequestMapping("/test")
public class UserContextTestController {

    /**
     * 测试获取当前用户信息
     * 需要先登录并在请求头中携带 Access Token
     */
    @GetMapping("/current-user")
    public Result<Map<String, Object>> getCurrentUser() {
        Map<String, Object> result = new HashMap<>();
        
        // 检查用户是否已登录
        if (!UserContextUtil.isLoggedIn()) {
            result.put("loggedIn", false);
            result.put("message", "用户未登录");
            return Result.success(result);
        }
        
        // 获取用户信息
        String userId = UserContextUtil.getCurrentUserId();
        String username = UserContextUtil.getCurrentUsername();
        String role = UserContextUtil.getCurrentUserRole();
        UserContextUtil.UserInfo userInfo = UserContextUtil.getCurrentUser();
        
        result.put("loggedIn", true);
        result.put("userId", userId);
        result.put("username", username);
        result.put("role", role);
        
        if (userInfo != null) {
            Map<String, Object> userInfoMap = new HashMap<>();
            userInfoMap.put("userId", userInfo.getUserId());
            userInfoMap.put("username", userInfo.getUsername());
            userInfoMap.put("role", userInfo.getRole());
            result.put("userInfo", userInfoMap);
        }
        
        log.info("获取当前用户信息: userId={}", userId);
        return Result.success(result);
    }
    
    /**
     * 测试仅获取用户ID
     */
    @GetMapping("/current-user-id")
    public Result<String> getCurrentUserId() {
        String userId = UserContextUtil.getCurrentUserId();
        
        if (userId == null) {
            return Result.error("AUTH_401", "用户未登录");
        }
        
        log.info("获取当前用户ID: {}", userId);
        return Result.success(userId);
    }
}
