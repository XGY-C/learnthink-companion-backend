package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ForumCommentLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ForumCommentLikeMapper extends BaseMapper<ForumCommentLike> {

    @Select("SELECT COUNT(*) FROM forum_comment_likes l " +
            "JOIN forum_comments c ON l.comment_id = c.id " +
            "WHERE c.user_id = #{userId} AND l.action = 'like' " +
            "AND c.deleted_at IS NULL")
    long countCommentLikesReceived(@Param("userId") String userId);
}
