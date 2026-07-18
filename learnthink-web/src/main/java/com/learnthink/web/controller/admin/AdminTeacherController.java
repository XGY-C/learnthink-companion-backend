package com.learnthink.web.controller.admin;

import com.learnthink.common.dto.admin.AdminTeacherResponse;
import com.learnthink.common.dto.admin.UpdateStatusRequest;
import com.learnthink.common.result.Result;
import com.learnthink.core.service.admin.AdminTeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/teachers")
@RequiredArgsConstructor
public class AdminTeacherController {

    private final AdminTeacherService adminTeacherService;

    @GetMapping
    public Result<List<AdminTeacherResponse>> listTeachers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        return Result.success(adminTeacherService.listTeachers(search, status));
    }

    @PostMapping
    public Result<AdminTeacherResponse> createTeacher(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String email = body.get("email");
        String password = body.getOrDefault("password", "123456");
        String displayName = body.get("displayName");
        String phone = body.get("phone");

        if (username == null || username.isBlank()) {
            return Result.error("用户名不能为空");
        }
        if (email == null || email.isBlank()) {
            return Result.error("邮箱不能为空");
        }

        return Result.success(
            adminTeacherService.createTeacher(username, email, password, displayName, phone),
            "教师账号已创建"
        );
    }

    @PutMapping("/{id}")
    public Result<AdminTeacherResponse> updateTeacher(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            return Result.success(
                adminTeacherService.updateTeacher(id, body.get("displayName"), body.get("email"), body.get("phone")),
                "教师信息已更新"
            );
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable String id, @RequestBody UpdateStatusRequest request) {
        try {
            adminTeacherService.updateTeacherStatus(id, request.getStatus());
            return Result.success();
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable String id) {
        try {
            adminTeacherService.resetPassword(id);
            return Result.success(null, "密码已重置为 123456");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteTeacher(@PathVariable String id) {
        try {
            adminTeacherService.deleteTeacher(id);
            return Result.success(null, "教师账号已删除");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
