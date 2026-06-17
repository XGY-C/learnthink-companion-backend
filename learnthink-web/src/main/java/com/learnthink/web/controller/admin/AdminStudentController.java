package com.learnthink.web.controller.admin;

import com.learnthink.common.dto.admin.AdminStudentResponse;
import com.learnthink.common.dto.admin.UpdateStatusRequest;
import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.repository.UserMapper;
import com.learnthink.core.service.UserService;
import com.learnthink.core.service.admin.AdminStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/students")
@RequiredArgsConstructor
public class AdminStudentController {

    private final AdminStudentService adminStudentService;
    private final UserService userService;
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @GetMapping
    public Result<List<AdminStudentResponse>> listStudents(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String major,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String courseId) {
        return Result.success(adminStudentService.listStudents(search, grade, major, status, courseId));
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable String id, @RequestBody UpdateStatusRequest request) {
        adminStudentService.updateStudentStatus(id, request.getStatus());
        return Result.success();
    }

    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable String id) {
        User user = userMapper.selectById(id);
        if (user == null) return Result.error(404, "用户不存在");
        if (!"student".equals(user.getRole())) return Result.error(400, "只能重置学生密码");
        String defaultPassword = "123456";
        user.setPasswordHash(passwordEncoder.encode(defaultPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return Result.success(null, "密码已重置为 " + defaultPassword);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteStudent(@PathVariable String id) {
        User user = userMapper.selectById(id);
        if (user == null) return Result.error(404, "用户不存在");
        if (!"student".equals(user.getRole())) return Result.error(400, "只能删除学生账号");
        userService.deleteById(id);
        return Result.success(null, "学生账号已删除");
    }
}
