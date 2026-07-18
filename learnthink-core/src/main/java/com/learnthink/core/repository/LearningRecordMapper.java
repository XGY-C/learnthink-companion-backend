package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.LearningRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface LearningRecordMapper extends BaseMapper<LearningRecord> {

    @Insert("INSERT INTO learning_records (id, user_id, course_id, plan_id, module_id, activity_id, resource_pack_id, resource_type, status, duration_seconds, completed_at) " +
            "VALUES (#{id}, #{userId}, #{courseId}, #{planId}, #{moduleId}, #{activityId}, #{resourcePackId}, #{resourceType}, #{status}, #{durationSeconds}, #{completedAt}) " +
            "ON DUPLICATE KEY UPDATE status = VALUES(status), duration_seconds = VALUES(duration_seconds), completed_at = VALUES(completed_at), updated_at = NOW()")
    int upsert(LearningRecord record);

    @Select("SELECT * FROM learning_records WHERE user_id = #{userId} AND plan_id = #{planId} AND module_id = #{moduleId} AND activity_id = #{activityId}")
    List<LearningRecord> findByActivity(@Param("userId") String userId, @Param("planId") String planId,
                                         @Param("moduleId") String moduleId, @Param("activityId") String activityId);

    @Select("SELECT COALESCE(SUM(duration_seconds), 0) FROM learning_records WHERE user_id = #{userId} AND course_id = #{courseId} AND DATE(created_at) = CURDATE()")
    int sumTodaySeconds(@Param("userId") String userId, @Param("courseId") String courseId);
}
