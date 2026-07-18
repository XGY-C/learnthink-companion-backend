package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("practice_session_items")
public class PracticeSessionItem {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String sessionId;
    private String questionId;
    private String attemptId;
    private Integer sortOrder;
    private Boolean isCorrect;
}
