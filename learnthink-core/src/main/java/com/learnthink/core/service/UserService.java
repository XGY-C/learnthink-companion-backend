package com.learnthink.core.service;

import com.learnthink.core.domain.entity.User;
import java.util.List;

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
}
