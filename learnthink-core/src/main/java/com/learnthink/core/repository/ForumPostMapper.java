package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnthink.core.domain.entity.ForumPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ForumPostMapper extends BaseMapper<ForumPost> {

    @Update("UPDATE forum_posts SET view_count = view_count + 1 WHERE id = #{postId} AND deleted_at IS NULL")
    void incrementViewCount(@Param("postId") String postId);

    @Update("UPDATE forum_posts SET like_count = like_count + #{delta} WHERE id = #{postId}")
    void updateLikeCount(@Param("postId") String postId, @Param("delta") int delta);

    @Update("UPDATE forum_posts SET dislike_count = dislike_count + #{delta} WHERE id = #{postId}")
    void updateDislikeCount(@Param("postId") String postId, @Param("delta") int delta);

    @Update("UPDATE forum_posts SET comment_count = comment_count + #{delta} WHERE id = #{postId}")
    void updateCommentCount(@Param("postId") String postId, @Param("delta") int delta);

    @Update("UPDATE forum_posts SET favorite_count = favorite_count + #{delta} WHERE id = #{postId}")
    void updateFavoriteCount(@Param("postId") String postId, @Param("delta") int delta);

    @Update("UPDATE forum_posts SET last_activity_at = #{time} WHERE id = #{postId}")
    void updateLastActivityAt(@Param("postId") String postId, @Param("time") java.time.LocalDateTime time);
}
