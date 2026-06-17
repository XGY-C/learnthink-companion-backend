package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("profile_signals")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileSignal {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String courseId;

    private String chatId;

    private String dimension;

    private String signalKey;

    private String value;

    private String source;

    private String status;

    private LocalDateTime createdAt;
}
