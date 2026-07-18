package com.learnthink.common.dto.forum;

import lombok.Data;

@Data
public class ReceivedCommentVO {
    private String id;
    private String postId;
    private String postTitle;
    private String content;
    private String commenterUserId;
    private String commenterName;
    private String commenterAvatar;
    private String createdAt;
    private boolean isReply;        // 是否是回复我的评论（true）还是回复我的帖子（false）
}
