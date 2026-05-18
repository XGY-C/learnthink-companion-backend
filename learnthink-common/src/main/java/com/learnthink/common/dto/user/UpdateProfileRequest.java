package com.learnthink.common.dto.user;

import lombok.Data;

/**
 * 个人资料更新请求
 */
@Data
public class UpdateProfileRequest {
    private String displayName;
    private String bio;
    private String major;
    private String grade;
    private String phone;
}
