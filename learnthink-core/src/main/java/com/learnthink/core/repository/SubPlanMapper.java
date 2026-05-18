package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.SubPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/**
 * 子计划 Mapper (v3.0)
 */
@Mapper
public interface SubPlanMapper extends BaseMapper<SubPlan> {

    @Select("SELECT * FROM sub_plans WHERE plan_id = #{planId}")
    List<SubPlan> findByPlanId(@Param("planId") String planId);

    @Select("SELECT * FROM sub_plans WHERE plan_id = #{planId} AND module_id = #{moduleId}")
    SubPlan findByPlanIdAndModuleId(@Param("planId") String planId, @Param("moduleId") String moduleId);

    @Update("UPDATE sub_plans SET sub_plan_json = #{subPlanJson}, version = #{version}, updated_at = NOW() WHERE id = #{id}")
    int updateSubPlan(@Param("id") String id, @Param("subPlanJson") String subPlanJson, @Param("version") Integer version);

    @Update("UPDATE sub_plans SET generation_status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateGenerationStatus(@Param("id") String id, @Param("status") String status);
}
