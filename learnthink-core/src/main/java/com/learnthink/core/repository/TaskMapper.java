package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    @Select("SELECT * FROM tasks WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<Task> findByUserId(@Param("userId") String userId);

    @Select("SELECT * FROM tasks WHERE user_id = #{userId} AND course_id = #{courseId} ORDER BY created_at DESC")
    List<Task> findByUserIdAndCourseId(@Param("userId") String userId, @Param("courseId") String courseId);

    @Select("SELECT * FROM tasks WHERE chat_id = #{chatId} AND status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED') ORDER BY created_at DESC")
    List<Task> findByChatId(@Param("chatId") String chatId);
}
