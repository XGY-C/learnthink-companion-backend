package com.learnthink.web.controller;

import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    
    private final UserService userService;

    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable String id) {
        User user = userService.findById(id);
        if (user != null) {
            return Result.success(user);
        } else {
            return Result.error("用户不存在");
        }
    }

    @GetMapping
    public Result<List<User>> getAll() {
        return Result.success(userService.findAll());
    }

    @PostMapping
    public Result<User> create(@RequestBody User user) {
        return Result.success(userService.save(user));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        userService.deleteById(id);
        return Result.success();
    }
}
