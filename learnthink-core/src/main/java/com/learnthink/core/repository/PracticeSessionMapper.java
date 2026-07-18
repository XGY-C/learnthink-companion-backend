package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnthink.core.domain.entity.PracticeSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PracticeSessionMapper extends BaseMapper<PracticeSession> {

    @Select("SELECT * FROM practice_sessions WHERE user_id = #{userId} AND course_id = #{courseId} ORDER BY created_at DESC")
    Page<PracticeSession> listByUser(@Param("userId") String userId, @Param("courseId") String courseId, Page<PracticeSession> page);

    @Update("UPDATE practice_sessions SET evaluation = #{evaluation}, updated_at = NOW() WHERE id = #{id}")
    int updateEvaluation(@Param("id") String id, @Param("evaluation") String evaluation);

    @Select("SELECT COALESCE(SUM(correct_count), 0) FROM practice_sessions " +
            "WHERE user_id = #{userId} AND course_id = #{courseId} AND completed = 1")
    int sumCorrectCount(@Param("userId") String userId, @Param("courseId") String courseId);

    @Select("SELECT COALESCE(SUM(question_count), 0) FROM practice_sessions " +
            "WHERE user_id = #{userId} AND course_id = #{courseId} AND completed = 1")
    int sumQuestionCount(@Param("userId") String userId, @Param("courseId") String courseId);
}
