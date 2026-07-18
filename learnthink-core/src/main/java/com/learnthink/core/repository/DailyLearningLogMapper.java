package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.DailyLearningLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface DailyLearningLogMapper extends BaseMapper<DailyLearningLog> {

    /**
     * 按日累加学习时长（心跳接口调用）
     */
    @Insert("INSERT INTO daily_learning_logs (id, user_id, course_id, log_date, learning_seconds) " +
            "VALUES (REPLACE(UUID(),'-',''), #{userId}, #{courseId}, #{logDate}, #{deltaSeconds}) " +
            "ON DUPLICATE KEY UPDATE learning_seconds = learning_seconds + VALUES(learning_seconds), updated_at = NOW()")
    int upsertDelta(@Param("userId") String userId,
                    @Param("courseId") String courseId,
                    @Param("logDate") LocalDate logDate,
                    @Param("deltaSeconds") int deltaSeconds);

    /**
     * 查询日期范围内每日学习时长（单课程模式）
     */
    @Select("SELECT * FROM daily_learning_logs " +
            "WHERE user_id = #{userId} AND course_id = #{courseId} " +
            "AND log_date BETWEEN #{startDate} AND #{endDate} " +
            "ORDER BY log_date")
    List<DailyLearningLog> findByDateRange(@Param("userId") String userId,
                                           @Param("courseId") String courseId,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);

    /**
     * 查询某用户所有课程的每日学习时长聚合（全课程模式）
     */
    @Select("SELECT log_date, SUM(learning_seconds) as learning_seconds " +
            "FROM daily_learning_logs " +
            "WHERE user_id = #{userId} AND log_date BETWEEN #{startDate} AND #{endDate} " +
            "GROUP BY log_date ORDER BY log_date")
    List<Map<String, Object>> findAllCourseByDateRange(@Param("userId") String userId,
                                                        @Param("startDate") LocalDate startDate,
                                                        @Param("endDate") LocalDate endDate);

    /**
     * 汇总指定日期范围内的学习秒数（单课程模式，用于本周学时实时计算）
     */
    @Select("SELECT COALESCE(SUM(learning_seconds), 0) FROM daily_learning_logs " +
            "WHERE user_id = #{userId} AND course_id = #{courseId} " +
            "AND log_date BETWEEN #{startDate} AND #{endDate}")
    int sumSecondsByDateRange(@Param("userId") String userId,
                              @Param("courseId") String courseId,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate);
}
