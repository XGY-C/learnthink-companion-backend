package com.learnthink.core.service;

import java.util.Map;

/**
 * Token存储服务接口（基于Redis）
 */
public interface TokenStorageService {
    
    /**
     * 生成不透明Token
     * @param byteLength 随机字节长度
     * @return 不透明Token字符串
     */
    String generateToken(int byteLength);
    
    /**
     * 存储Access Token
     * @param accessToken Access Token
     * @param userId 用户ID
     * @param role 用户角色
     * @param tokenVer Token版本号
     */
    void storeAccessToken(String accessToken, String userId, String role, Integer tokenVer);
    
    /**
     * 存储Refresh Token
     * @param refreshToken Refresh Token
     * @param userId 用户ID
     * @param tokenVer Token版本号
     */
    void storeRefreshToken(String refreshToken, String userId, Integer tokenVer);
    
    /**
     * 获取Access Token信息
     * @param accessToken Access Token
     * @return Token信息Map，包含userId、role、tokenVer
     */
    Map<String, Object> getAccessTokenInfo(String accessToken);
    
    /**
     * 获取Refresh Token信息
     * @param refreshToken Refresh Token
     * @return Token信息Map，包含userId、tokenVer
     */
    Map<String, Object> getRefreshTokenInfo(String refreshToken);
    
    /**
     * 删除Access Token
     * @param accessToken Access Token
     */
    void deleteAccessToken(String accessToken);
    
    /**
     * 删除Refresh Token
     * @param refreshToken Refresh Token
     * @param userId 用户ID
     */
    void deleteRefreshToken(String refreshToken, String userId);
    
    /**
     * 获取用户Token版本号
     * @param userId 用户ID
     * @return Token版本号
     */
    Integer getTokenVersion(String userId);
    
    /**
     * 递增用户Token版本号（改密时使用）
     * @param userId 用户ID
     * @return 新版本号
     */
    Integer incrementTokenVersion(String userId);
    
    /**
     * 添加Refresh Token到用户索引
     * @param userId 用户ID
     * @param refreshToken Refresh Token
     */
    void addRefreshTokenToUserIndex(String userId, String refreshToken);
    
    /**
     * 从用户索引中移除Refresh Token
     * @param userId 用户ID
     * @param refreshToken Refresh Token
     */
    void removeRefreshTokenFromUserIndex(String userId, String refreshToken);
    
    /**
     * 获取用户所有Refresh Token的hash值
     * @param userId 用户ID
     * @return Refresh Token hash集合
     */
    java.util.Set<String> getUserRefreshTokenHashes(String userId);
    
    /**
     * 删除用户所有Refresh Token
     * @param userId 用户ID
     */
    void deleteUserAllRefreshTokens(String userId);
}
