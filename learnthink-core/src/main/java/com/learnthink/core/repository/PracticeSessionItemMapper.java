package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.PracticeSessionItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PracticeSessionItemMapper extends BaseMapper<PracticeSessionItem> {

    @Select("SELECT * FROM practice_session_items WHERE session_id = #{sessionId} ORDER BY sort_order")
    List<PracticeSessionItem> listBySession(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) FROM practice_session_items psi " +
            "JOIN practice_sessions ps ON psi.session_id = ps.id " +
            "WHERE ps.user_id = #{userId} AND ps.course_id = #{courseId} " +
            "AND psi.is_correct IS NOT NULL")
    int countAnsweredByUserAndCourse(@Param("userId") String userId, @Param("courseId") String courseId);
}
