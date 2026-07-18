package com.learnthink.core.directanswer.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 推理链↔前置知识交叉引用（占位）。
 * 对应表 direct_answer_anchors。
 */
@Data
@TableName("direct_answer_anchors")
public class DirectAnswerAnchor {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String sessionId;
    private String stepId;
    private String knowledgeCardId;
    private String knowledgeLabel;
}
