package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ResourcePack;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;

@Mapper
public interface ResourcePackMapper extends BaseMapper<ResourcePack> {

    /**
     * 统计用户在某课程下未删除的资源包总数
     */
    @Select("SELECT COUNT(*) FROM resource_packs " +
            "WHERE user_id = #{userId} AND course_id = #{courseId} AND deleted_at IS NULL")
    int countByUserAndCourse(@Param("userId") String userId, @Param("courseId") String courseId);

    /**
     * 统计用户在某课程下指定时间之后的资源包数（用于本周资源数）
     */
    @Select("SELECT COUNT(*) FROM resource_packs " +
            "WHERE user_id = #{userId} AND course_id = #{courseId} AND deleted_at IS NULL " +
            "AND created_at >= #{since}")
    int countByUserAndCourseSince(@Param("userId") String userId,
                                  @Param("courseId") String courseId,
                                  @Param("since") LocalDateTime since);
}
