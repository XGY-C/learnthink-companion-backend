package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.Note;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    @Select("SELECT * FROM notes WHERE user_id = #{userId} AND course_id = #{courseId} AND deleted_at IS NULL ORDER BY created_at DESC")
    List<Note> listByUserAndCourse(@Param("userId") String userId, @Param("courseId") String courseId);

    @Select("SELECT * FROM notes WHERE user_id = #{userId} AND course_id = #{courseId} AND resource_pack_id = #{resourcePackId} AND deleted_at IS NULL ORDER BY created_at DESC")
    List<Note> listByUserAndResource(@Param("userId") String userId, @Param("courseId") String courseId, @Param("resourcePackId") String resourcePackId);

    @Select("SELECT COUNT(*) FROM notes WHERE user_id = #{userId} AND course_id = #{courseId} AND deleted_at IS NULL")
    long countByUserAndCourse(@Param("userId") String userId, @Param("courseId") String courseId);
}
