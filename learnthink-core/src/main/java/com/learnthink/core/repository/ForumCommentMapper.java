package com.learnthink.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.learnthink.core.domain.entity.ForumComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

@Mapper
public interface ForumCommentMapper extends BaseMapper<ForumComment> {

    @Update("UPDATE forum_comments SET like_count = like_count + #{delta} WHERE id = #{commentId}")
    void updateLikeCount(@Param("commentId") String commentId, @Param("delta") int delta);

    @Update("UPDATE forum_comments SET dislike_count = dislike_count + #{delta} WHERE id = #{commentId}")
    void updateDislikeCount(@Param("commentId") String commentId, @Param("delta") int delta);

    @Update("UPDATE forum_comments SET reply_count = reply_count + #{delta} WHERE id = #{commentId}")
    void updateReplyCount(@Param("commentId") String commentId, @Param("delta") int delta);

    // ===== My Forum Activity =====

    @Select("SELECT c.id, c.post_id AS postId, p.title AS postTitle, c.content, " +
            "c.like_count AS likeCount, c.reply_count AS replyCount, c.created_at AS createdAt " +
            "FROM forum_comments c " +
            "JOIN forum_posts p ON c.post_id = p.id " +
            "WHERE c.user_id = #{userId} AND c.deleted_at IS NULL AND p.deleted_at IS NULL " +
            "ORDER BY c.created_at DESC")
    IPage<Map<String, Object>> selectMyComments(IPage<Map<String, Object>> page,
                                                 @Param("userId") String userId);

    @Select("SELECT c.id, c.post_id AS postId, p.title AS postTitle, c.content, " +
            "c.user_id AS commenterUserId, " +
            "u.display_name AS commenterName, u.avatar_url AS commenterAvatar, " +
            "c.created_at AS createdAt, c.parent_id AS parentId, " +
            "CASE WHEN pc.user_id = #{userId} THEN 1 ELSE 0 END AS isReply " +
            "FROM forum_comments c " +
            "JOIN forum_posts p ON c.post_id = p.id " +
            "LEFT JOIN users u ON c.user_id = u.id " +
            "LEFT JOIN forum_comments pc ON c.parent_id = pc.id " +
            "WHERE p.user_id = #{userId} AND c.user_id <> #{userId} " +
            "AND c.deleted_at IS NULL AND p.deleted_at IS NULL " +
            "ORDER BY c.created_at DESC")
    IPage<Map<String, Object>> selectReceivedComments(IPage<Map<String, Object>> page,
                                                       @Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM forum_comments c " +
            "JOIN forum_posts p ON c.post_id = p.id " +
            "WHERE p.user_id = #{userId} AND c.user_id <> #{userId} " +
            "AND c.deleted_at IS NULL AND p.deleted_at IS NULL")
    long countReceivedComments(@Param("userId") String userId);
}
