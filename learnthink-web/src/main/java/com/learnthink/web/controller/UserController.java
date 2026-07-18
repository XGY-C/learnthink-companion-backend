package com.learnthink.web.controller;

import com.learnthink.common.dto.user.ChangePasswordRequest;
import com.learnthink.common.dto.user.DailyActivityResponse;
import com.learnthink.common.dto.user.DailyDetailResponse;
import com.learnthink.common.dto.user.LearningHeartbeatRequest;
import com.learnthink.common.dto.user.LearningStatsResponse;
import com.learnthink.common.dto.user.NotificationResponse;
import com.learnthink.common.dto.user.UpdateProfileRequest;
import com.learnthink.common.dto.user.UserInfoResponse;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.converter.UserConverter;
import com.learnthink.core.domain.entity.User;
import com.learnthink.core.service.NotificationService;
import com.learnthink.core.service.UserService;
import com.learnthink.core.service.UserStatsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AliOSSUtil aliOSSUtil;
    private final NotificationService notificationService;
    private final UserStatsService userStatsService;

    // ==================== 当前用户自我管理 ====================

    @GetMapping("/me")
    public Result<UserInfoResponse> getMe() {
        String userId = UserContextUtil.getCurrentUserId();
        User user = userService.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }
        return Result.success(UserConverter.toUserInfoResponse(user));
    }

    @PutMapping("/profile")
    public Result<UserInfoResponse> updateProfile(@RequestBody UpdateProfileRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        User user = userService.updateProfile(userId, request);
        return Result.success(UserConverter.toUserInfoResponse(user));
    }

    @PutMapping("/avatar")
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        String userId = UserContextUtil.getCurrentUserId();

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/png") && !contentType.equals("image/jpeg"))) {
            return Result.error("仅支持 JPG 和 PNG 格式的头像");
        }

        if (file.getSize() > 2 * 1024 * 1024) {
            return Result.error("头像文件不能超过 2MB");
        }

        try {
            String avatarUrl = aliOSSUtil.upload(file, "avatars/" + userId + "/");
            userService.updateAvatar(userId, avatarUrl);
            return Result.success(avatarUrl);
        } catch (IOException e) {
            return Result.error("头像上传失败: " + e.getMessage());
        }
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        try {
            userService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
            return Result.success();
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    // ==================== 通知 ====================

    @GetMapping("/me/notifications")
    public Result<List<NotificationResponse>> getNotifications(
            @RequestParam(defaultValue = "false") boolean includeUnpushed,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(notificationService.getUserNotifications(userId, includeUnpushed, page, size));
    }

    @PutMapping("/me/notifications/{id}/read")
    public Result<Void> markNotificationRead(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        notificationService.markAsRead(id, userId);
        return Result.success();
    }

    @PutMapping("/me/notifications/read-all")
    public Result<Void> markAllNotificationsRead() {
        String userId = UserContextUtil.getCurrentUserId();
        notificationService.markAllAsRead(userId);
        return Result.success();
    }

    @GetMapping("/me/notifications/unread-count")
    public Result<java.util.Map<String, Object>> getUnreadCount() {
        String userId = UserContextUtil.getCurrentUserId();
        int count = notificationService.getUnreadCount(userId);
        return Result.success(java.util.Map.of("unreadCount", count));
    }

    @DeleteMapping("/me/notifications/{id}")
    public Result<Void> deleteNotification(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        notificationService.deleteNotification(id, userId);
        return Result.success();
    }

    // ==================== 学习统计 ====================

    @GetMapping("/me/stats")
    public Result<LearningStatsResponse> getStats(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(userStatsService.getStats(userId, courseId));
    }

    /**
     * 学习心跳上报
     */
    @PostMapping("/me/learning-heartbeat")
    public Result<Void> heartbeat(@RequestBody @Valid LearningHeartbeatRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        userStatsService.recordHeartbeat(userId, request.getCourseId(), request.getDeltaSeconds());
        return Result.success();
    }

    /**
     * 获取日历热力图数据
     */
    @GetMapping("/me/daily-activity")
    public Result<DailyActivityResponse> getDailyActivity(
            @RequestParam(required = false) String courseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(userStatsService.getDailyActivity(userId, courseId, startDate, endDate));
    }

    /**
     * 获取某日学习详情
     */
    @GetMapping("/me/daily-detail")
    public Result<DailyDetailResponse> getDailyDetail(
            @RequestParam(required = false) String courseId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(userStatsService.getDailyDetail(userId, courseId, date));
    }

    @DeleteMapping("/me")
    public Result<Void> deleteMe() {
        String userId = UserContextUtil.getCurrentUserId();
        userService.deleteById(userId);
        return Result.success();
    }

    // ==================== 通用 CRUD（管理用途） ====================

    @GetMapping("/{id}")
    public Result<UserInfoResponse> getById(@PathVariable String id) {
        User user = userService.findById(id);
        if (user != null) {
            return Result.success(UserConverter.toUserInfoResponse(user));
        } else {
            return Result.error("用户不存在");
        }
    }

    @GetMapping
    public Result<List<UserInfoResponse>> getAll() {
        List<User> users = userService.findAll();
        List<UserInfoResponse> result = users.stream()
                .map(UserConverter::toUserInfoResponse)
                .toList();
        return Result.success(result);
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
