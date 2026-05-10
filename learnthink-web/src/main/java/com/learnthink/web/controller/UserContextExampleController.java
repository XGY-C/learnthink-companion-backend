package com.learnthink.web.controller;

import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户上下文使用示例控制器
 * 展示如何在实际业务中使用 UserContextUtil
 */
@Slf4j
@RestController
@RequestMapping("/example")
public class UserContextExampleController {

    /**
     * 示例1：创建资源时自动关联当前用户
     */
    @PostMapping("/resources")
    public Result<Map<String, Object>> createResource(@RequestBody Map<String, Object> resourceData) {
        // 直接获取当前用户ID，无需从请求参数中传递
        String userId = UserContextUtil.getCurrentUserId();
        
        if (userId == null) {
            return Result.error("AUTH_401", "用户未登录");
        }
        
        // 模拟创建资源
        Map<String, Object> result = new HashMap<>();
        result.put("resourceId", "resource-" + System.currentTimeMillis());
        result.put("userId", userId);  // 自动关联当前用户
        result.put("data", resourceData);
        result.put("createdAt", System.currentTimeMillis());
        
        log.info("用户 {} 创建了资源: {}", userId, resourceData);
        
        return Result.success(result);
    }
    
    /**
     * 示例2：查询当前用户的个人数据
     */
    @GetMapping("/my-profile")
    public Result<Map<String, Object>> getMyProfile() {
        // 获取当前用户ID
        String userId = UserContextUtil.getCurrentUserId();
        String role = UserContextUtil.getCurrentUserRole();
        
        if (userId == null) {
            return Result.error("AUTH_401", "用户未登录");
        }
        
        // 模拟查询用户画像
        Map<String, Object> profile = new HashMap<>();
        profile.put("userId", userId);
        profile.put("role", role);
        profile.put("username", "user_" + userId.substring(0, 8));
        profile.put("courseProgress", 75.5);
        
        log.info("用户 {} 查询了个人画像", userId);
        
        return Result.success(profile);
    }
    
    /**
     * 示例3：权限检查 - 只有管理员可以访问
     */
    @GetMapping("/admin/dashboard")
    public Result<Map<String, Object>> getAdminDashboard() {
        String userId = UserContextUtil.getCurrentUserId();
        String role = UserContextUtil.getCurrentUserRole();
        
        if (userId == null) {
            return Result.error("AUTH_401", "用户未登录");
        }
        
        // 检查用户角色
        if (!"admin".equals(role)) {
            return Result.error("AUTH_403", "需要管理员权限");
        }
        
        Map<String, Object> dashboard = new HashMap<>();
        dashboard.put("totalUsers", 1250);
        dashboard.put("activeTasks", 45);
        dashboard.put("systemStatus", "healthy");
        
        log.info("管理员 {} 访问了管理面板", userId);
        
        return Result.success(dashboard);
    }
    
    /**
     * 示例4：记录用户操作日志
     */
    @PostMapping("/actions/log")
    public Result<Void> logUserAction(@RequestBody Map<String, Object> actionData) {
        String userId = UserContextUtil.getCurrentUserId();
        
        if (userId == null) {
            return Result.error("AUTH_401", "用户未登录");
        }
        
        // 模拟记录操作日志
        log.info("用户操作日志 - 用户ID: {}, 操作: {}, 详情: {}", 
                userId, actionData.get("action"), actionData);
        
        // 在实际应用中，这里会将日志保存到数据库
        
        return Result.success(null);
    }
    
    /**
     * 示例5：更新用户数据时自动验证所有权
     */
    @PutMapping("/resources/{resourceId}")
    public Result<Map<String, Object>> updateResource(
            @PathVariable String resourceId,
            @RequestBody Map<String, Object> updateData) {
        
        String currentUserId = UserContextUtil.getCurrentUserId();
        
        if (currentUserId == null) {
            return Result.error("AUTH_401", "用户未登录");
        }
        
        // 模拟从数据库获取资源信息
        String resourceOwnerId = "user-test-001"; // 假设资源所有者
        
        // 验证当前用户是否有权限修改该资源
        if (!currentUserId.equals(resourceOwnerId)) {
            return Result.error("AUTH_403", "无权修改此资源");
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("resourceId", resourceId);
        result.put("updatedBy", currentUserId);
        result.put("updateData", updateData);
        result.put("updatedAt", System.currentTimeMillis());
        
        log.info("用户 {} 更新了资源 {}", currentUserId, resourceId);
        
        return Result.success(result);
    }
}
