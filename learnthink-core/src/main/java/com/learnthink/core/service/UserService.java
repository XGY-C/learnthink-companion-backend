package com.learnthink.core.service;

import com.learnthink.core.domain.entity.User;
import java.util.List;

import com.learnthink.common.dto.user.UpdateProfileRequest;

/**
 * 用户服务接口
 */
public interface UserService {
    User findById(String id);
    User findByUsername(String username);
    User findByEmail(String email);
    List<User> findAll();
    User save(User user);
    void deleteById(String id);

    /** 更新个人资料（仅允许 displayName, bio, major, grade, phone） */
    User updateProfile(String userId, UpdateProfileRequest request);

    /** 更新头像 URL */
    void updateAvatar(String userId, String avatarUrl);

    /** 修改密码（验证旧密码） */
    void changePassword(String userId, String oldPassword, String newPassword);
}
