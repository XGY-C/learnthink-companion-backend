package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.LearningPlanVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 大计划版本快照 Mapper (v3.0)
 */
@Mapper
public interface LearningPlanVersionMapper extends BaseMapper<LearningPlanVersion> {

    @Select("SELECT * FROM learning_plan_versions WHERE plan_id = #{planId} ORDER BY version DESC")
    List<LearningPlanVersion> findByPlanId(@Param("planId") String planId);

    @Select("SELECT * FROM learning_plan_versions WHERE plan_id = #{planId} ORDER BY version DESC LIMIT 1")
    LearningPlanVersion findLatestByPlanId(@Param("planId") String planId);
}
