package com.learnthink.core.service;

import com.learnthink.common.dto.chat.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话服务核心接口。
 * 负责管理会话生命周期、消息流式编排、画像分析及会话历史查询。
 */
public interface ChatService {

    /**
     * 创建新对话会话。
     * 初始化会话元数据并绑定课程上下文，返回会话标识供后续消息交互。
     *
     * @param userId  当前用户ID
     * @param request 会话启动请求（含课程ID、初始意图等）
     * @return 会话启动响应（含chatId、初始欢迎语）
     */
    ChatStartResponse startChat(String userId, ChatStartRequest request);

    /**
     * 流式发送消息并返回SSE事件流。
     * 核心业务流：意图探测 → RAG检索 → 多智能体编排 → 增量更新画像 → 资源生成触发。
     * 通过SseEvent.isNamed()区分命名事件（如intent_detected、profile_updated）与文本块。
     *
     * @param userId  当前用户ID
     * @param chatId  目标会话ID
     * @param request 消息发送请求（含用户输入、上下文标记等）
     * @return SSE事件流（Flux<SseEvent>），前端需按事件类型分流处理
     */
    Flux<SseEvent> streamMessage(String userId, String chatId, ChatSendRequest request);

    /**
     * 获取指定会话的历史消息列表。
     * 按时间戳升序返回，支持前端渲染完整对话时间线。
     *
     * @param userId 当前用户ID
     * @param chatId 目标会话ID
     * @return 对话消息响应（含消息列表、分页信息）
     */
    ChatMessagesResponse getMessages(String userId, String chatId);

    /**
     * 查询用户的会话列表。
     * 支持按课程ID过滤，返回会话摘要（标题、最后消息时间、未读数等）。
     *
     * @param userId   当前用户ID
     * @param courseId 可选的课程ID过滤条件
     * @return 会话DTO列表，按最后活跃时间降序排列
     */
    List<ChatSessionDto> getSessions(String userId, String courseId);

    /**
     * 异步分析并更新用户画像。
     * 从对话历史中抽取认知维度（知识掌握度、学习偏好、薄弱点），执行增量合并。
     * 通常在对话结束后或达到触发阈值时调用。
     *
     * @param userId 当前用户ID
     * @param chatId 目标会话ID
     * @return 画像摘要DTO（含各维度评分、建议学习路径）
     */
    ProfileSummaryDto analyzeProfile(String userId, String chatId);

    /**
     * 删除指定会话及其关联数据。
     * 级联清理消息记录、任务状态及临时资源，释放存储空间。
     *
     * @param userId 当前用户ID
     * @param chatId 目标会话ID
     */
    void deleteSession(String userId, String chatId);

    /**
     * 通知后端会话已结束，触发画像两步流水线更新（handleChatEnd）。
     *
     * @param userId 当前用户ID
     * @param chatId 目标会话ID
     */
    void endSession(String userId, String chatId);
}
