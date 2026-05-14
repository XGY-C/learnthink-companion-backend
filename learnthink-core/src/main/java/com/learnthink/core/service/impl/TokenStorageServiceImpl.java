package com.learnthink.core.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.service.TokenStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Token存储服务实现（基于Redis）
 */
@Slf4j
@Service
public class TokenStorageServiceImpl implements TokenStorageService {
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    public TokenStorageServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }
    
    // Access Token TTL: 2小时
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(2);
    // Refresh Token TTL: 7天
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    
    // Redis Key前缀
    private static final String AT_PREFIX = "at:";
    private static final String RT_PREFIX = "rt:";
    private static final String TOKEN_VER_PREFIX = "user:token_ver:";
    private static final String USER_RT_INDEX_PREFIX = "user:refresh_tokens:";
    
    @Override
    public String generateToken(int byteLength) {
        SecureRandom secureRandom = new SecureRandom();
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return UUID.randomUUID().toString() + "-" + bytesToHex(bytes);
    }
    
    @Override
    public void storeAccessToken(String accessToken, String userId, String role, Integer tokenVer) {
        try {
            Map<String, Object> tokenData = Map.of(
                "userId", userId,
                "role", role,
                "tokenVer", tokenVer
            );
            String jsonValue = objectMapper.writeValueAsString(tokenData);
            String key = AT_PREFIX + accessToken;
            redisTemplate.opsForValue().set(key, jsonValue, ACCESS_TOKEN_TTL);
            log.debug("Access Token stored for user: {}", userId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize access token data", e);
            throw new RuntimeException("Failed to store access token", e);
        }
    }
    
    @Override
    public void storeRefreshToken(String refreshToken, String userId, Integer tokenVer) {
        try {
            Map<String, Object> tokenData = Map.of(
                "userId", userId,
                "tokenVer", tokenVer
            );
            String jsonValue = objectMapper.writeValueAsString(tokenData);
            String key = RT_PREFIX + refreshToken;
            redisTemplate.opsForValue().set(key, jsonValue, REFRESH_TOKEN_TTL);
            log.debug("Refresh Token stored for user: {}", userId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize refresh token data", e);
            throw new RuntimeException("Failed to store refresh token", e);
        }
    }
    
    @Override
    public Map<String, Object> getAccessTokenInfo(String accessToken) {
        String key = AT_PREFIX + accessToken;
        String jsonValue = redisTemplate.opsForValue().get(key);
        if (jsonValue == null) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonValue, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize access token data", e);
            return null;
        }
    }
    
    @Override
    public Map<String, Object> getRefreshTokenInfo(String refreshToken) {
        String key = RT_PREFIX + refreshToken;
        String jsonValue = redisTemplate.opsForValue().get(key);
        if (jsonValue == null) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonValue, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize refresh token data", e);
            return null;
        }
    }
    
    @Override
    public void deleteAccessToken(String accessToken) {
        String key = AT_PREFIX + accessToken;
        redisTemplate.delete(key);
        log.debug("Access Token deleted");
    }
    
    @Override
    public void deleteRefreshToken(String refreshToken, String userId) {
        String key = RT_PREFIX + refreshToken;
        redisTemplate.delete(key);
        removeRefreshTokenFromUserIndex(userId, refreshToken);
        log.debug("Refresh Token deleted for user: {}", userId);
    }
    
    @Override
    public Integer getTokenVersion(String userId) {
        String key = TOKEN_VER_PREFIX + userId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0; // 默认版本号
        }
        return Integer.parseInt(value);
    }
    
    @Override
    public Integer incrementTokenVersion(String userId) {
        String key = TOKEN_VER_PREFIX + userId;
        Long newVersion = redisTemplate.opsForValue().increment(key);
        return newVersion != null ? newVersion.intValue() : 1;
    }
    
    @Override
    public void addRefreshTokenToUserIndex(String userId, String refreshToken) {
        String key = USER_RT_INDEX_PREFIX + userId;
        String hash = hashToken(refreshToken);
        redisTemplate.opsForSet().add(key, hash);
        log.debug("Refresh Token added to user index: {}", userId);
    }
    
    @Override
    public void removeRefreshTokenFromUserIndex(String userId, String refreshToken) {
        String key = USER_RT_INDEX_PREFIX + userId;
        String hash = hashToken(refreshToken);
        redisTemplate.opsForSet().remove(key, hash);
        log.debug("Refresh Token removed from user index: {}", userId);
    }
    
    @Override
    public Set<String> getUserRefreshTokenHashes(String userId) {
        String key = USER_RT_INDEX_PREFIX + userId;
        return redisTemplate.opsForSet().members(key);
    }
    
    @Override
    public void deleteUserAllRefreshTokens(String userId) {
        String key = USER_RT_INDEX_PREFIX + userId;
        redisTemplate.delete(key);
        log.debug("All refresh tokens deleted for user: {}", userId);
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * 对Token进行hash（用于索引存储）
     */
    private String hashToken(String token) {
        // 简单hash：使用SHA-256的前16个字符
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 8; i++) { // 取前8字节（16个hex字符）
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            // 降级方案：直接使用token的hashCode
            return String.format("%08x", token.hashCode());
        }
    }
}
