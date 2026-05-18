package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user_course_enrollments")
public class UserCourseEnrollment {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String courseId;
    private LocalDateTime enrolledAt;
}
