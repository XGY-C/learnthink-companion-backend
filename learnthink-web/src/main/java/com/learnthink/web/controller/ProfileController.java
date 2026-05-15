package com.learnthink.web.controller;

import com.learnthink.common.dto.profile.ProfileChatRequest;
import com.learnthink.common.dto.profile.ProfileChatResponse;
import com.learnthink.common.dto.profile.ProfileVersionsResponse;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @PostMapping("/chat")
    public Result<ProfileChatResponse> chat(@RequestBody ProfileChatRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        ProfileChatResponse response = profileService.processChat(userId, request);
        return Result.success(response);
    }

    @GetMapping
    public Result<Map<String, Object>> getProfile(@RequestParam("course_id") String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(profileService.getProfile(userId, courseId));
    }

    @GetMapping("/versions")
    public Result<ProfileVersionsResponse> versions(@RequestParam("course_id") String courseId,
                                                    @RequestParam(value = "limit", defaultValue = "10") int limit) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(new ProfileVersionsResponse(
            profileService.getProfileVersions(userId, courseId, limit)
        ));
    }
}
