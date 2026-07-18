package com.learnthink.web.controller;

import com.learnthink.common.dto.forum.*;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.service.ForumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/forum")
@RequiredArgsConstructor
public class ForumController {

    private final ForumService forumService;

    // ===== Tags =====

    @GetMapping("/tags")
    public Result<List<TagVO>> listTags() {
        return Result.success(forumService.listTags());
    }

    @GetMapping("/available-resources")
    public Result<List<ResourceVO>> listAvailableResources() {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.listAvailableResources(userId));
    }

    // ===== Resource Content =====

    @GetMapping("/resources/{resourceItemId}")
    public Result<Map<String, Object>> getResourceContent(@PathVariable String resourceItemId) {
        return Result.success(forumService.getResourceContent(resourceItemId));
    }

    // ===== Posts =====

    @GetMapping("/posts")
    public Result<PageResult<PostVO>> listPosts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String courseId,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String tab,
            @RequestParam(required = false) String keyword) {
        String userId = UserContextUtil.getCurrentUserId();
        PageResult<PostVO> result = forumService.listPosts(userId, courseId, tags, sort, tab, keyword, page, size);
        return Result.success(result);
    }

    @GetMapping("/posts/{id}")
    public Result<PostDetailVO> getPostDetail(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.getPostDetail(userId, id));
    }

    @PostMapping("/posts")
    public Result<PostDetailVO> createPost(@RequestBody CreatePostRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.createPost(userId, req));
    }

    @PutMapping("/posts/{id}")
    public Result<Void> updatePost(@PathVariable String id, @RequestBody UpdatePostRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        forumService.updatePost(userId, id, req);
        return Result.success();
    }

    @DeleteMapping("/posts/{id}")
    public Result<Void> deletePost(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        forumService.deletePost(userId, id);
        return Result.success();
    }

    @PostMapping("/posts/{id}/view")
    public Result<Void> recordView(@PathVariable String id) {
        forumService.recordView(id);
        return Result.success();
    }

    // ===== Comments =====

    @GetMapping("/posts/{postId}/comments")
    public Result<List<CommentVO>> listComments(@PathVariable String postId) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.listComments(userId, postId));
    }

    @PostMapping("/posts/{postId}/comments")
    public Result<CommentVO> createComment(@PathVariable String postId, @RequestBody CreateCommentRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.createComment(userId, postId, req));
    }

    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable String commentId) {
        String userId = UserContextUtil.getCurrentUserId();
        forumService.deleteComment(userId, commentId);
        return Result.success();
    }

    // ===== Likes =====

    @PostMapping("/like")
    public Result<Map<String, Object>> toggleLike(@RequestBody LikeRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.toggleLike(userId, req.getTargetType(), req.getTargetId(), req.getAction()));
    }

    @DeleteMapping("/like")
    public Result<Void> removeLike(@RequestBody LikeRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        forumService.removeLike(userId, req.getTargetType(), req.getTargetId());
        return Result.success();
    }

    // ===== Favorites =====

    @PostMapping("/favorites/{postId}")
    public Result<Void> addFavorite(@PathVariable String postId) {
        String userId = UserContextUtil.getCurrentUserId();
        forumService.addFavorite(userId, postId);
        return Result.success();
    }

    @DeleteMapping("/favorites/{postId}")
    public Result<Void> removeFavorite(@PathVariable String postId) {
        String userId = UserContextUtil.getCurrentUserId();
        forumService.removeFavorite(userId, postId);
        return Result.success();
    }

    @GetMapping("/favorites")
    public Result<List<PostVO>> listFavorites() {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.listFavorites(userId));
    }

    // ===== Follows =====

    @PostMapping("/follows/{userId}")
    public Result<Void> followUser(@PathVariable String userId) {
        String currentUserId = UserContextUtil.getCurrentUserId();
        forumService.followUser(currentUserId, userId);
        return Result.success();
    }

    @DeleteMapping("/follows/{userId}")
    public Result<Void> unfollowUser(@PathVariable String userId) {
        String currentUserId = UserContextUtil.getCurrentUserId();
        forumService.unfollowUser(currentUserId, userId);
        return Result.success();
    }

    // ===== Reports =====

    @PostMapping("/reports")
    public Result<Void> report(@RequestBody ReportRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        forumService.report(userId, req.getTargetType(), req.getTargetId(), req.getReason());
        return Result.success();
    }

    // ===== Upload =====

    @PostMapping("/upload/image")
    public Result<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.uploadImage(userId, file));
    }

    @PostMapping("/upload/file")
    public Result<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.uploadFile(userId, file));
    }

    // ===== My Forum Activity =====

    @GetMapping("/me/overview")
    public Result<ForumActivityOverviewVO> getMyOverview() {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.getMyOverview(userId));
    }

    @GetMapping("/me/posts")
    public Result<PageResult<PostVO>> listMyPosts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.listMyPosts(userId, page, size));
    }

    @GetMapping("/me/comments")
    public Result<PageResult<MyCommentVO>> listMyComments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.listMyComments(userId, page, size));
    }

    @GetMapping("/me/received-comments")
    public Result<PageResult<ReceivedCommentVO>> listReceivedComments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(forumService.listReceivedComments(userId, page, size));
    }
}
