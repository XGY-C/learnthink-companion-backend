package com.learnthink.common.dto.forum;

import lombok.Data;

import java.util.List;

@Data
public class CommentVO {
    private String id;
    private String postId;
    private String parentId;
    private String rootId;
    private String userId;
    private String userName;
    private String userAvatar;
    private String content;
    private Integer likeCount;
    private Integer dislikeCount;
    private Integer replyCount;
    private String userLiked;
    private List<CommentVO> children;
    private String createdAt;
}
