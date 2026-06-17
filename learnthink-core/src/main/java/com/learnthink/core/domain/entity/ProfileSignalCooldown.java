package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("profile_signal_cooldown")
public class ProfileSignalCooldown {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private String signalKey;

    private LocalDateTime cooldownUntil;

    private LocalDateTime createdAt;
}
