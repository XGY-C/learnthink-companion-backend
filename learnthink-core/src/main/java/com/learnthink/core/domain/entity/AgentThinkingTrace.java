package com.learnthink.core.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Agent 思考链记录 — 持久化 Agent 的内部推理过程
 */
@Data
@TableName("agent_thinking_traces")
public class AgentThinkingTrace {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 任务ID（任务流使用，对话流场景可为空） */
    private String taskId;

    /** 会话ID（对话流使用，引用 profile_chats.id） */
    private String chatId;

    /** Agent 标识：Conversation / Profile / Retriever / Planner / Generator / Reviewer */
    private String agentName;

    /** Agent 角色描述 */
    private String agentRole;

    /** 流水线阶段 */
    private String phase;

    /** 输入上下文摘要 */
    private String context;

    /** Agent 观察到了什么 */
    private String observation;

    /** Agent 的推理过程 */
    private String thought;

    /** Agent 的决策结论 */
    private String decision;

    /** high / medium / low */
    private String confidenceLevel;

    /** 对应对话轮次（从 1 开始），用于历史消息重建思考链 */
    private Integer roundNum;

    /** autonomous / response_to_agent / system_prompt */
    @TableField("`trigger`")
    private String trigger;

    /** 回复目标 trace ID */
    private String inResponseTo;

    private LocalDateTime createdAt;
}
