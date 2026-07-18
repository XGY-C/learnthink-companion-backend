package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    @Select("<script>" +
            "SELECT * FROM questions WHERE user_id = #{userId} AND course_id = #{courseId} " +
            "AND deleted_at IS NULL AND status = 'published' " +
            "<if test='questionTypes != null and questionTypes.size() > 0'>" +
            "AND question_type IN " +
            "<foreach item='t' collection='questionTypes' open='(' separator=',' close=')'>#{t}</foreach> " +
            "</if>" +
            "<if test='questionTypes == null or questionTypes.size() == 0'>" +
            "AND question_type IN ('single_choice','multiple_choice','true_false','fill_blank','essay') " +
            "</if>" +
            "<if test='kpIds != null and kpIds.size() > 0'> AND kp_id IN " +
            "<foreach item='k' collection='kpIds' open='(' separator=',' close=')'>#{k}</foreach></if> " +
            "<if test='difficulty != null'> AND difficulty = #{difficulty} </if>" +
            "ORDER BY RAND() LIMIT #{limit}" +
            "</script>")
    List<Question> selectByKpIdsRandom(@Param("userId") String userId, @Param("courseId") String courseId,
                                       @Param("kpIds") List<String> kpIds,
                                       @Param("difficulty") Integer difficulty,
                                       @Param("questionTypes") List<String> questionTypes,
                                       @Param("limit") int limit);
}
