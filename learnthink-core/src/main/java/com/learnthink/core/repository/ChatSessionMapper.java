package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对应于 chat_sessions 表。
 * incrMessageCount 使用 MySQL 行级锁保证并发安全。
 */
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 原子递增 message_count，同时更新 updated_at 和 current_round（行级锁，并发安全）
     * 对应 allocateSeq() 的 UPDATE 操作。
     */
    @Update("UPDATE chat_sessions SET message_count = message_count + 1, updated_at = NOW(), current_round = (message_count + 1) DIV 2 WHERE id = #{sessionId}")
    void incrMessageCount(String sessionId);

    /**
     * 更新会话标题（异步 LLM 摘要生成后调用）
     */
    @Update("UPDATE chat_sessions SET title = #{title} WHERE id = #{sessionId}")
    void updateTitle(String sessionId, String title);

    @Select("SELECT * FROM chat_sessions WHERE user_id = #{userId} " +
            "AND created_at >= #{start} AND created_at < #{end} " +
            "AND (#{courseId} IS NULL OR course_id = #{courseId}) " +
            "ORDER BY created_at")
    List<ChatSession> findByUserAndDateRange(@Param("userId") String userId,
                                             @Param("courseId") String courseId,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);
}
