package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.QuestionAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionAttemptMapper extends BaseMapper<QuestionAttempt> {

    @Select("SELECT qa.question_id FROM question_attempts qa " +
            "INNER JOIN questions q ON q.id = qa.question_id " +
            "WHERE qa.user_id = #{userId} AND qa.course_id = #{courseId} " +
            "AND qa.is_correct = 0 AND q.deleted_at IS NULL " +
            "GROUP BY qa.question_id " +
            "ORDER BY MAX(qa.created_at) DESC LIMIT #{limit}")
    List<String> selectWrongQuestionIds(@Param("userId") String userId,
                                        @Param("courseId") String courseId,
                                        @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM (" +
            "  SELECT question_id, is_correct, " +
            "    ROW_NUMBER() OVER (PARTITION BY question_id ORDER BY created_at DESC) AS rn " +
            "  FROM question_attempts " +
            "  WHERE user_id = #{userId} AND course_id = #{courseId} " +
            ") t WHERE rn = 1 AND is_correct = 0")
    int countWrongQuestions(@Param("userId") String userId, @Param("courseId") String courseId);
}
