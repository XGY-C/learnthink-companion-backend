package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.UserStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserStatsMapper extends BaseMapper<UserStats> {

    /**
     * 累加学习时长（心跳接口调用）
     */
    @Update("UPDATE user_stats SET total_learning_minutes = total_learning_minutes + #{minutes}, calculated_at = NOW() " +
            "WHERE user_id = #{userId} AND course_id = #{courseId}")
    int addLearningMinutes(@Param("userId") String userId, @Param("courseId") String courseId, @Param("minutes") int minutes);
}
