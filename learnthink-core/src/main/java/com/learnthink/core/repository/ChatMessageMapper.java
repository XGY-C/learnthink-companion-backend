package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 对应于 chat_messages 表。
 * Phase 5 H3: 新增分页查询方法。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY seq_num ASC")
    List<ChatMessage> selectBySessionId(String sessionId);

    /**
     * 分页加载会话消息（按 seq_num 降序获取最新的 N 条，再反序）。
     * 避免全量加载导致内存和带宽问题。
     */
    @Select("SELECT * FROM (SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY seq_num DESC LIMIT #{offset}, #{size}) t ORDER BY seq_num ASC")
    List<ChatMessage> selectBySessionIdPaged(@Param("sessionId") String sessionId,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    /**
     * 获取会话消息总数。
     */
    @Select("SELECT COUNT(*) FROM chat_messages WHERE session_id = #{sessionId}")
    long countBySessionId(@Param("sessionId") String sessionId);
}
