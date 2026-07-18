package com.learnthink.core.service.chat;

import com.learnthink.core.domain.entity.ChatSession;
import com.learnthink.core.repository.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * seq 原子分配器实现。
 * UPDATE chat_sessions SET message_count = message_count + 1 的行级锁保证并发安全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {

    private final ChatSessionMapper chatSessionMapper;

    @Override
    @Transactional
    public MessageSeq allocateSeq(String sessionId, String role) {
        // 1. 原子递增 message_count（MySQL UPDATE 行级锁串行化）
        chatSessionMapper.incrMessageCount(sessionId);

        // 2. 读取递增后的值
        ChatSession session = chatSessionMapper.selectById(sessionId);
        int seqNum = session.getMessageCount();           // 1, 2, 3...
        int roundNum = (seqNum + 1) / 2;                  // 1, 1, 2, 2, 3, 3...

        return new MessageSeq(seqNum, roundNum);
    }
}
