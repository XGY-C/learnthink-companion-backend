package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.QuizAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QuizAttemptMapper extends BaseMapper<QuizAttempt> {

    @Select("SELECT * FROM quiz_attempts WHERE user_id = #{userId} " +
            "AND created_at >= #{start} AND created_at < #{end} " +
            "AND (#{courseId} IS NULL OR course_id = #{courseId}) " +
            "ORDER BY created_at")
    List<QuizAttempt> findByUserAndDateRange(@Param("userId") String userId,
                                             @Param("courseId") String courseId,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);
}
