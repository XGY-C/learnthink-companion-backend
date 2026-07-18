package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.Notebook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NotebookMapper extends BaseMapper<Notebook> {

    @Select("SELECT * FROM notebooks WHERE user_id = #{userId} AND course_id = #{courseId} AND deleted_at IS NULL ORDER BY sort_order ASC, created_at ASC")
    List<Notebook> listByUserAndCourse(@Param("userId") String userId, @Param("courseId") String courseId);

    @Select("SELECT * FROM notebooks WHERE user_id = #{userId} AND course_id = #{courseId} AND is_default = 1 AND deleted_at IS NULL LIMIT 1")
    Notebook findDefaultByCourse(@Param("userId") String userId, @Param("courseId") String courseId);
}
