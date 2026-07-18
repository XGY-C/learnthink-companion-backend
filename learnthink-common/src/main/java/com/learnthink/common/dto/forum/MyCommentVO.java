package com.learnthink.common.dto.forum;

import lombok.Data;

@Data
public class MyCommentVO {
    private String id;
    private String postId;
    private String postTitle;       // 所属帖子标题（用于跳转）
    private String content;
    private String createdAt;
    private long likeCount;
    private long replyCount;
}
