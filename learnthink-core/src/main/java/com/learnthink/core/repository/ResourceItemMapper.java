package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ResourceItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResourceItemMapper extends BaseMapper<ResourceItem> {

    @Select("<script>" +
            "SELECT * FROM resource_items " +
            "<if test='forceIndex'>FORCE INDEX (idx_ri_user_course_del_created) </if>" +
            "WHERE user_id = #{userId} AND course_id = #{courseId} AND deleted_at IS NULL " +
            "<if test='folderMode == 0'>AND folder_id IS NULL </if>" +
            "<if test='folderMode == 2'>AND folder_id = #{folderId} </if>" +
            "<if test='type != null'>AND type = #{type} </if>" +
            "<if test='confidence != null'>AND confidence = #{confidence} </if>" +
            "<choose>" +
            "<when test='sortType == 1'>ORDER BY title ASC </when>" +
            "<when test='sortType == 2'>ORDER BY quality_score DESC </when>" +
            "<otherwise>ORDER BY created_at DESC </otherwise>" +
            "</choose>" +
            "LIMIT #{offset}, #{size}" +
            "</script>")
    List<ResourceItem> selectListFiles(@Param("userId") String userId,
                                       @Param("courseId") String courseId,
                                       @Param("folderMode") int folderMode,
                                       @Param("folderId") String folderId,
                                       @Param("type") String type,
                                       @Param("confidence") String confidence,
                                       @Param("forceIndex") boolean forceIndex,
                                       @Param("sortType") int sortType,
                                       @Param("offset") long offset,
                                       @Param("size") long size);

    @Select("<script>" +
            "SELECT COUNT(*) FROM resource_items " +
            "WHERE user_id = #{userId} AND course_id = #{courseId} AND deleted_at IS NULL " +
            "<if test='folderMode == 0'>AND folder_id IS NULL </if>" +
            "<if test='folderMode == 2'>AND folder_id = #{folderId} </if>" +
            "<if test='type != null'>AND type = #{type} </if>" +
            "<if test='confidence != null'>AND confidence = #{confidence} </if>" +
            "</script>")
    long countListFiles(@Param("userId") String userId,
                        @Param("courseId") String courseId,
                        @Param("folderMode") int folderMode,
                        @Param("folderId") String folderId,
                        @Param("type") String type,
                        @Param("confidence") String confidence);
}
