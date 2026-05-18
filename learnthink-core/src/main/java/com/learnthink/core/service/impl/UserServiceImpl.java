package com.learnthink.core.service.impl;

import com.learnthink.common.dto.user.UpdateProfileRequest;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.repository.UserMapper;
import com.learnthink.core.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户服务实现
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User findById(String id) {
        return userMapper.selectById(id);
    }

    @Override
    public User findByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public User findByEmail(String email) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, email);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public List<User> findAll() {
        return userMapper.selectList(null);
    }

    @Override
    public User save(User user) {
        if (user.getId() == null || user.getId().isEmpty()) {
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);
        } else {
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);
        }
        return user;
    }

    @Override
    public void deleteById(String id) {
        userMapper.deleteById(id);
    }

    @Override
    public User updateProfile(String userId, UpdateProfileRequest request) {
        User user = findById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getMajor() != null) {
            user.setMajor(request.getMajor());
        }
        if (request.getGrade() != null) {
            user.setGrade(request.getGrade());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        save(user);
        return user;
    }

    @Override
    public void updateAvatar(String userId, String avatarUrl) {
        User user = findById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        user.setAvatarUrl(avatarUrl);
        save(user);
    }

    @Override
    public void changePassword(String userId, String oldPassword, String newPassword) {
        User user = findById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("旧密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        save(user);
    }
}
