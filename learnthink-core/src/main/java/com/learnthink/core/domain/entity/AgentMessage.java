package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Agent 间协作消息 — 持久化 Agent 之间的双向交互记录
 */
@Data
@TableName("agent_messages")
public class AgentMessage {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String taskId;

    /** 发送方 Agent */
    private String fromAgent;

    /** 接收方 Agent（NULL=广播） */
    private String toAgent;

    /** Agent 角色 */
    private String agentRole;

    /**
     * 协作动作类型：
     * coverage_report / plan_adjusted / revision_request /
     * revision_applied / revision_approved / parallel_dispatch / fallback_decision
     */
    private String action;

    /** 人类可读消息 */
    private String message;

    /** 结构化详情 JSON */
    private String detailJson;

    private LocalDateTime createdAt;
}
