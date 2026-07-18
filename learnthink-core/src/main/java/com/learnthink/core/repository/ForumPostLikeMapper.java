package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ForumPostLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ForumPostLikeMapper extends BaseMapper<ForumPostLike> {

    @Select("SELECT COUNT(*) FROM forum_post_likes l " +
            "JOIN forum_posts p ON l.post_id = p.id " +
            "WHERE p.user_id = #{userId} AND l.action = 'like' " +
            "AND p.deleted_at IS NULL")
    long countPostLikesReceived(@Param("userId") String userId);
}
