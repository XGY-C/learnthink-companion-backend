package com.learnthink.core.tutoring.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.GuidedStepStateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface GuidedStepStateMapper extends BaseMapper<GuidedStepStateEntity> {

    @Select("SELECT * FROM tutoring_guided_step WHERE tutoring_session_id = #{sessionId} " +
            "AND status = 'waiting_answer' ORDER BY step_order LIMIT 1")
    GuidedStepStateEntity findWaitingBySession(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM tutoring_guided_step WHERE tutoring_session_id = #{sessionId} ORDER BY step_order")
    List<GuidedStepStateEntity> findAllBySession(@Param("sessionId") String sessionId);
}
