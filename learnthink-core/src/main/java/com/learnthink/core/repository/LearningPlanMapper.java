package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.LearningPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 学习大计划 Mapper (v3.0)
 */
@Mapper
public interface LearningPlanMapper extends BaseMapper<LearningPlan> {

    @Select("SELECT * FROM learning_plans WHERE user_id = #{userId} AND course_id = #{courseId} AND status != 'archived'")
    LearningPlan findByUserIdAndCourseId(@Param("userId") String userId, @Param("courseId") String courseId);

    @Update("UPDATE learning_plans SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);

    @Update("UPDATE learning_plans SET current_version = #{version}, plan_json = #{planJson}, updated_at = NOW() WHERE id = #{id}")
    int updatePlan(@Param("id") String id, @Param("version") Integer version, @Param("planJson") String planJson);
}
