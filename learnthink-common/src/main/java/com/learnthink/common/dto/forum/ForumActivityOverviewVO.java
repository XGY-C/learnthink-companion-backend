package com.learnthink.common.dto.forum;

import lombok.Data;

@Data
public class ForumActivityOverviewVO {
    private long postCount;              // 我的发帖数
    private long commentCount;           // 我的评论数
    private long receivedCommentCount;   // 别人给我的评论数
    private long likeReceivedCount;      // 获赞总数（帖子获赞 + 评论获赞，实时聚合）
    private long postLikeCount;          // 帖子获赞数（明细）
    private long commentLikeCount;       // 评论获赞数（明细）
}
