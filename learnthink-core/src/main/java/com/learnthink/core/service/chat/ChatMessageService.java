package com.learnthink.core.service.chat;

/**
 * 消息序列号分配服务。
 * 使用 chat_sessions.message_count 原子递增保证并发安全。
 *
 * 对应数据库重构缺口1 的 seq 分配方案。
 */
public interface ChatMessageService {

    /**
     * 原子分配 seqNum 和 roundNum。
     * @param sessionId 会话ID
     * @param role      user | assistant
     * @return 包含 seqNum 和 roundNum 的记录
     */
    MessageSeq allocateSeq(String sessionId, String role);

    record MessageSeq(int seqNum, int roundNum) {}
}
