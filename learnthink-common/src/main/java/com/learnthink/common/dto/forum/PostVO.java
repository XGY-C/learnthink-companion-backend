package com.learnthink.common.dto.forum;

import lombok.Data;

import java.util.List;

@Data
public class PostVO {
    private String id;
    private String userId;
    private String userName;
    private String userAvatar;
    private String courseId;
    private String title;
    private String summary;
    private List<String> tags;
    private String type;
    private Boolean isPinned;
    private Boolean isFeatured;
    private Integer viewCount;
    private Integer likeCount;
    private Integer dislikeCount;
    private Integer commentCount;
    private Integer favoriteCount;
    private Integer shareCount;
    private String userLiked;
    private Boolean userFavorited;
    private String lastActivityAt;
    private String createdAt;
}
