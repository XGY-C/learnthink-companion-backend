package com.learnthink.core.domain.converter;

import com.learnthink.common.dto.user.UserInfoResponse;
import com.learnthink.core.domain.entity.User;

/**
 * 用户DTO转换器
 */
public class UserConverter {
    
    /**
     * 将User实体转换为UserInfoResponse DTO
     * @param user 用户实体
     * @return 用户信息响应DTO
     */
    public static UserInfoResponse toUserInfoResponse(User user) {
        if (user == null) {
            return null;
        }
        
        return UserInfoResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .major(user.getMajor())
                .grade(user.getGrade())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null)
                .build();
    }
}
