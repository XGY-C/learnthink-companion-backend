package com.learnthink.common.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 用户上下文工具类
 * 使用 ThreadLocal 存储当前请求的用户信息，方便在任意位置获取当前用户ID
 */
@Slf4j
public class UserContextUtil {

    /**
     * 用户信息内部类
     */
    public static class UserInfo {
        private String userId;
        private String username;
        private String role;

        public UserInfo(String userId, String username, String role) {
            this.userId = userId;
            this.username = username;
            this.role = role;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    // 使用 ThreadLocal 存储当前用户信息
    private static final ThreadLocal<UserInfo> USER_CONTEXT = new ThreadLocal<>();

    /**
     * 设置当前用户信息
     *
     * @param userInfo 用户信息
     */
    public static void setCurrentUser(UserInfo userInfo) {
        USER_CONTEXT.set(userInfo);
        log.debug("设置当前用户上下文: userId={}", userInfo != null ? userInfo.getUserId() : null);
    }

    /**
     * 获取当前用户信息
     *
     * @return 用户信息，如果未登录则返回 null
     */
    public static UserInfo getCurrentUser() {
        return USER_CONTEXT.get();
    }

    /**
     * 获取当前用户ID
     *
     * @return 用户ID，如果未登录则返回 null
     */
    public static String getCurrentUserId() {
        UserInfo userInfo = USER_CONTEXT.get();
        return userInfo != null ? userInfo.getUserId() : null;
    }

    /**
     * 获取当前用户名
     *
     * @return 用户名，如果未登录则返回 null
     */
    public static String getCurrentUsername() {
        UserInfo userInfo = USER_CONTEXT.get();
        return userInfo != null ? userInfo.getUsername() : null;
    }

    /**
     * 获取当前用户角色
     *
     * @return 用户角色，如果未登录则返回 null
     */
    public static String getCurrentUserRole() {
        UserInfo userInfo = USER_CONTEXT.get();
        return userInfo != null ? userInfo.getRole() : null;
    }

    /**
     * 清除当前用户上下文
     * 必须在请求结束后调用，防止内存泄漏
     */
    public static void clear() {
        UserInfo userInfo = USER_CONTEXT.get();
        if (userInfo != null) {
            log.debug("清除用户上下文: userId={}", userInfo.getUserId());
        }
        USER_CONTEXT.remove();
    }

    /**
     * 检查当前用户是否已登录
     *
     * @return true 如果用户已登录，false 否则
     */
    public static boolean isLoggedIn() {
        return USER_CONTEXT.get() != null;
    }
}
