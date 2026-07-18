package com.learnthink.core.service;

import com.learnthink.common.dto.forum.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface ForumService {

    // ===== Tags =====

    List<TagVO> listTags();

    List<ResourceVO> listAvailableResources(String userId);

    // ===== Resource Content =====

    Map<String, Object> getResourceContent(String resourceItemId);

    // ===== Posts =====
    PageResult<PostVO> listPosts(String userId, String courseId,
                                 String tagId, String sort, String tab, String keyword,
                                 int page, int size);

    PostDetailVO getPostDetail(String userId, String postId);

    PostDetailVO createPost(String userId, CreatePostRequest req);

    void updatePost(String userId, String postId, UpdatePostRequest req);

    void deletePost(String userId, String postId);

    void recordView(String postId);

    // ===== Comments =====
    List<CommentVO> listComments(String userId, String postId);

    CommentVO createComment(String userId, String postId, CreateCommentRequest req);

    void deleteComment(String userId, String commentId);

    // ===== Likes =====
    Map<String, Object> toggleLike(String userId, String targetType, String targetId, String action);

    void removeLike(String userId, String targetType, String targetId);

    // ===== Favorites =====
    void addFavorite(String userId, String postId);

    void removeFavorite(String userId, String postId);

    List<PostVO> listFavorites(String userId);

    // ===== Follows =====
    void followUser(String userId, String targetUserId);

    void unfollowUser(String userId, String targetUserId);

    // ===== Reports =====
    void report(String userId, String targetType, String targetId, String reason);

    // ===== Upload =====
    Map<String, Object> uploadImage(String userId, MultipartFile file);

    Map<String, Object> uploadFile(String userId, MultipartFile file);

    // ===== My Forum Activity =====

    ForumActivityOverviewVO getMyOverview(String userId);

    PageResult<PostVO> listMyPosts(String userId, int page, int size);

    PageResult<MyCommentVO> listMyComments(String userId, int page, int size);

    PageResult<ReceivedCommentVO> listReceivedComments(String userId, int page, int size);
}
