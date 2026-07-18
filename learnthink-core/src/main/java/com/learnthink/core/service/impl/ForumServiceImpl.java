package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnthink.common.dto.forum.*;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.common.util.AliOSSUtil;
import com.learnthink.core.domain.entity.*;
import com.learnthink.core.repository.*;
import com.learnthink.core.service.ForumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForumServiceImpl implements ForumService {

    private final ForumTagMapper forumTagMapper;
    private final ForumPostMapper forumPostMapper;
    private final ForumPostTagMapper forumPostTagMapper;
    private final ForumPostResourceMapper forumPostResourceMapper;
    private final ForumPostFileMapper forumPostFileMapper;
    private final ForumCommentMapper forumCommentMapper;
    private final ForumPostLikeMapper forumPostLikeMapper;
    private final ForumCommentLikeMapper forumCommentLikeMapper;
    private final ForumFavoriteMapper forumFavoriteMapper;
    private final ForumFollowMapper forumFollowMapper;
    private final ForumReportMapper forumReportMapper;
    private final UserMapper userMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final AliOSSUtil aliOSSUtil;

    // ===== Tags =====

    @Override
    public List<TagVO> listTags() {
        LambdaQueryWrapper<ForumTag> wrapper = new LambdaQueryWrapper<ForumTag>()
                .eq(ForumTag::getEnabled, 1)
                .isNull(ForumTag::getDeletedAt)
                .orderByAsc(ForumTag::getSortOrder);
        return forumTagMapper.selectList(wrapper).stream()
                .map(this::toTagVO)
                .toList();
    }

    @Override
    public List<ResourceVO> listAvailableResources(String userId) {
        LambdaQueryWrapper<ResourcePack> packWrapper = new LambdaQueryWrapper<ResourcePack>()
                .eq(ResourcePack::getUserId, userId)
                .isNull(ResourcePack::getDeletedAt);
        List<ResourcePack> packs = resourcePackMapper.selectList(packWrapper);
        if (packs.isEmpty()) return Collections.emptyList();

        List<String> packIds = packs.stream().map(ResourcePack::getId).toList();
        LambdaQueryWrapper<ResourceItem> itemWrapper = new LambdaQueryWrapper<ResourceItem>()
                .in(ResourceItem::getPackId, packIds)
                .eq(ResourceItem::getStatus, "ready")
                .isNull(ResourceItem::getDeletedAt);
        return resourceItemMapper.selectList(itemWrapper).stream()
                .map(ri -> {
                    ResourceVO vo = new ResourceVO();
                    vo.setId(ri.getId());
                    vo.setResourceItemId(ri.getId());
                    vo.setTitle(ri.getTitle());
                    vo.setType(ri.getType());
                    vo.setPackId(ri.getPackId());
                    return vo;
                })
                .toList();
    }

    // ===== Resource Content =====

    @Override
    public Map<String, Object> getResourceContent(String resourceItemId) {
        ResourceItem ri = resourceItemMapper.selectById(resourceItemId);
        if (ri == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }

        List<ForumPostResource> refs = forumPostResourceMapper.selectList(
                new LambdaQueryWrapper<ForumPostResource>()
                        .eq(ForumPostResource::getResourceItemId, resourceItemId));
        if (refs.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该资源未被任何帖子引用，不可访问");
        }

        List<String> postIds = refs.stream().map(ForumPostResource::getPostId).toList();
        Long aliveCount = forumPostMapper.selectCount(
                new LambdaQueryWrapper<ForumPost>()
                        .in(ForumPost::getId, postIds)
                        .isNull(ForumPost::getDeletedAt));
        if (aliveCount == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该资源未被任何帖子引用，不可访问");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceItemId", ri.getId());
        result.put("packId", ri.getPackId());
        result.put("title", ri.getTitle());
        result.put("type", ri.getType());
        result.put("confidence", ri.getConfidence());
        result.put("qualityScore", ri.getQualityScore());

        boolean previewable = List.of("doc", "reading", "code").contains(ri.getType());
        result.put("isPreviewable", previewable);

        String content = null;
        String contentMime = ri.getContentMime();
        if (ri.getMetadataJson() != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode node = mapper.readTree(ri.getMetadataJson());
                if (node.has("content")) {
                    content = node.get("content").asText();
                }
            } catch (Exception e) {
                log.warn("Failed to parse metadataJson for resource {}", resourceItemId, e);
            }
        }
        result.put("content", content);
        result.put("contentMime", contentMime);

        if (ri.getSourcesJson() != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                result.put("sources", mapper.readValue(ri.getSourcesJson(), List.class));
            } catch (Exception e) {
                result.put("sources", Collections.emptyList());
            }
        } else {
            result.put("sources", Collections.emptyList());
        }

        return result;
    }

    // ===== Posts =====

    @Override
    public PageResult<PostVO> listPosts(String userId, String courseId,
                                        String tagId, String sort, String tab, String keyword,
                                        int page, int size) {
        LambdaQueryWrapper<ForumPost> wrapper = new LambdaQueryWrapper<ForumPost>()
                .isNull(ForumPost::getDeletedAt);

        if (StringUtils.hasText(courseId)) {
            wrapper.eq(ForumPost::getCourseId, courseId);
        }
        if ("featured".equals(tab)) {
            wrapper.eq(ForumPost::getIsFeatured, 1);
        } else if ("mine".equals(tab)) {
            wrapper.eq(ForumPost::getUserId, userId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ForumPost::getTitle, keyword)
                    .or().like(ForumPost::getSummary, keyword)
                    .or().like(ForumPost::getContent, keyword));
        }
        if (StringUtils.hasText(tagId)) {
            List<ForumPostTag> postTags = forumPostTagMapper.selectList(
                    new LambdaQueryWrapper<ForumPostTag>().eq(ForumPostTag::getTagId, tagId));
            if (postTags.isEmpty()) {
                return PageResult.<PostVO>builder().records(Collections.emptyList()).total(0).page(page).size(size).build();
            }
            List<String> postIds = postTags.stream().map(ForumPostTag::getPostId).toList();
            wrapper.in(ForumPost::getId, postIds);
        }

        wrapper.orderByDesc(ForumPost::getIsPinned);
        if ("hottest".equals(sort)) {
            wrapper.orderByDesc(ForumPost::getLikeCount);
            wrapper.orderByDesc(ForumPost::getCommentCount);
        } else {
            wrapper.orderByDesc(ForumPost::getLastActivityAt);
        }

        Page<ForumPost> result = forumPostMapper.selectPage(new Page<>(page, size), wrapper);
        if (result.getRecords().isEmpty()) {
            return PageResult.<PostVO>builder().records(Collections.emptyList()).total(0).page(page).size(size).build();
        }

        List<String> postIds = result.getRecords().stream().map(ForumPost::getId).toList();
        List<String> userIds = result.getRecords().stream().map(ForumPost::getUserId).distinct().toList();

        Map<String, User> userMap = batchGetUsers(userIds);
        Map<String, List<String>> tagMap = batchGetTagNames(postIds);
        Set<String> likedPostIds = batchGetLikedPostIds(userId, postIds);
        Set<String> favoritedPostIds = batchGetFavoritedPostIds(userId, postIds);

        List<PostVO> vos = result.getRecords().stream()
                .map(post -> toPostVO(post, userMap, tagMap, likedPostIds, favoritedPostIds))
                .toList();

        return PageResult.<PostVO>builder()
                .records(vos)
                .total(result.getTotal())
                .page(page)
                .size(size)
                .build();
    }

    @Override
    public PostDetailVO getPostDetail(String userId, String postId) {
        ForumPost post = getActivePost(postId);
        User author = userMapper.selectById(post.getUserId());
        List<String> tags = getTagNamesForPost(postId);
        List<ResourceVO> resources = getResourcesForPost(postId);
        List<FileVO> files = getFilesForPost(postId);

        String userLiked = "none";
        ForumPostLike postLike = forumPostLikeMapper.selectOne(
                new LambdaQueryWrapper<ForumPostLike>()
                        .eq(ForumPostLike::getUserId, userId)
                        .eq(ForumPostLike::getPostId, postId));
        if (postLike != null) userLiked = postLike.getAction();

        boolean userFavorited = forumFavoriteMapper.selectCount(
                new LambdaQueryWrapper<ForumFavorite>()
                        .eq(ForumFavorite::getUserId, userId)
                        .eq(ForumFavorite::getPostId, postId)) > 0;

        boolean userFollowed = forumFollowMapper.selectCount(
                new LambdaQueryWrapper<ForumFollow>()
                        .eq(ForumFollow::getFollowerId, userId)
                        .eq(ForumFollow::getFolloweeId, post.getUserId())) > 0;

        PostDetailVO vo = new PostDetailVO();
        fillPostVO(vo, post, author, tags, userLiked, userFavorited);
        vo.setContent(post.getContent());
        vo.setResources(resources);
        vo.setFiles(files);
        vo.setUserFollowed(userFollowed);
        return vo;
    }

    @Override
    @Transactional
    public PostDetailVO createPost(String userId, CreatePostRequest req) {
        ForumPost post = new ForumPost();
        post.setUserId(userId);
        post.setCourseId(StringUtils.hasText(req.getCourseId()) ? req.getCourseId() : null);
        post.setTitle(req.getTitle());
        post.setContent(req.getContent());
        post.setSummary(generateSummary(req.getContent()));
        post.setType(StringUtils.hasText(req.getType()) ? req.getType() : "post");
        post.setIsPinned(0);
        post.setIsFeatured(0);
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setDislikeCount(0);
        post.setCommentCount(0);
        post.setFavoriteCount(0);
        post.setShareCount(0);
        post.setLastActivityAt(LocalDateTime.now());
        post.setCreatedAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        forumPostMapper.insert(post);

        savePostTags(post.getId(), req.getTagIds());
        savePostResources(post.getId(), req.getResourceItemIds());
        savePostFiles(post.getId(), req.getFiles());

        return getPostDetail(userId, post.getId());
    }

    @Override
    @Transactional
    public void updatePost(String userId, String postId, UpdatePostRequest req) {
        ForumPost post = getActivePostForOwner(userId, postId);

        if (req.getTitle() != null) post.setTitle(req.getTitle());
        if (req.getContent() != null) {
            post.setContent(req.getContent());
            post.setSummary(generateSummary(req.getContent()));
        }
        if (req.getType() != null) post.setType(req.getType());
        post.setUpdatedAt(LocalDateTime.now());
        forumPostMapper.updateById(post);

        if (req.getTagIds() != null) {
            forumPostTagMapper.delete(new LambdaQueryWrapper<ForumPostTag>()
                    .eq(ForumPostTag::getPostId, postId));
            savePostTags(postId, req.getTagIds());
        }
        if (req.getResourceItemIds() != null) {
            forumPostResourceMapper.delete(new LambdaQueryWrapper<ForumPostResource>()
                    .eq(ForumPostResource::getPostId, postId));
            savePostResources(postId, req.getResourceItemIds());
        }
        if (req.getFiles() != null) {
            forumPostFileMapper.delete(new LambdaQueryWrapper<ForumPostFile>()
                    .eq(ForumPostFile::getPostId, postId));
            savePostFiles(postId, req.getFiles());
        }
    }

    @Override
    @Transactional
    public void deletePost(String userId, String postId) {
        ForumPost post = getActivePostForOwner(userId, postId);
        post.setDeletedAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        forumPostMapper.updateById(post);
    }

    @Override
    public void recordView(String postId) {
        forumPostMapper.incrementViewCount(postId);
    }

    // ===== Comments =====

    @Override
    public List<CommentVO> listComments(String userId, String postId) {
        List<ForumComment> allComments = forumCommentMapper.selectList(
                new LambdaQueryWrapper<ForumComment>()
                        .eq(ForumComment::getPostId, postId)
                        .isNull(ForumComment::getDeletedAt)
                        .orderByAsc(ForumComment::getCreatedAt));
        if (allComments.isEmpty()) return Collections.emptyList();

        List<String> commentIds = allComments.stream().map(ForumComment::getId).toList();
        List<String> userIds = allComments.stream().map(ForumComment::getUserId).distinct().toList();
        Map<String, User> userMap = batchGetUsers(userIds);

        Map<String, String> commentLikeMap = new HashMap<>();
        if (StringUtils.hasText(userId)) {
            List<ForumCommentLike> likes = forumCommentLikeMapper.selectList(
                    new LambdaQueryWrapper<ForumCommentLike>()
                            .eq(ForumCommentLike::getUserId, userId)
                            .in(ForumCommentLike::getCommentId, commentIds));
            for (ForumCommentLike l : likes) {
                commentLikeMap.put(l.getCommentId(), l.getAction());
            }
        }

        Map<String, List<ForumComment>> repliesByRoot = new LinkedHashMap<>();
        List<ForumComment> rootComments = new ArrayList<>();
        for (ForumComment c : allComments) {
            if (c.getRootId() == null) {
                rootComments.add(c);
            } else {
                repliesByRoot.computeIfAbsent(c.getRootId(), k -> new ArrayList<>()).add(c);
            }
        }

        return rootComments.stream()
                .map(root -> toCommentVO(root, userMap, commentLikeMap,
                        repliesByRoot.getOrDefault(root.getId(), Collections.emptyList())))
                .toList();
    }

    @Override
    @Transactional
    public CommentVO createComment(String userId, String postId, CreateCommentRequest req) {
        ForumPost post = getActivePost(postId);

        ForumComment comment = new ForumComment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setContent(req.getContent());
        comment.setLikeCount(0);
        comment.setDislikeCount(0);
        comment.setReplyCount(0);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(LocalDateTime.now());

        if (StringUtils.hasText(req.getParentId())) {
            ForumComment parent = forumCommentMapper.selectById(req.getParentId());
            if (parent == null || parent.getDeletedAt() != null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "父评论不存在");
            }
            comment.setParentId(parent.getId());
            comment.setRootId(parent.getRootId() != null ? parent.getRootId() : parent.getId());
            forumCommentMapper.updateReplyCount(parent.getId(), 1);
        } else {
            comment.setParentId(null);
            comment.setRootId(null);
        }

        forumCommentMapper.insert(comment);
        forumPostMapper.updateCommentCount(postId, 1);
        forumPostMapper.updateLastActivityAt(postId, LocalDateTime.now());

        User author = userMapper.selectById(userId);
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setPostId(postId);
        vo.setParentId(comment.getParentId());
        vo.setRootId(comment.getRootId());
        vo.setUserId(userId);
        vo.setUserName(author != null ? author.getDisplayName() : null);
        vo.setUserAvatar(author != null ? author.getAvatarUrl() : null);
        vo.setContent(comment.getContent());
        vo.setLikeCount(0);
        vo.setDislikeCount(0);
        vo.setReplyCount(0);
        vo.setUserLiked("none");
        vo.setChildren(Collections.emptyList());
        vo.setCreatedAt(formatTime(comment.getCreatedAt()));
        return vo;
    }

    @Override
    @Transactional
    public void deleteComment(String userId, String commentId) {
        ForumComment comment = forumCommentMapper.selectById(commentId);
        if (comment == null || comment.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除此评论");
        }
        comment.setDeletedAt(LocalDateTime.now());
        comment.setUpdatedAt(LocalDateTime.now());
        forumCommentMapper.updateById(comment);
        forumPostMapper.updateCommentCount(comment.getPostId(), -1);
        if (StringUtils.hasText(comment.getParentId())) {
            forumCommentMapper.updateReplyCount(comment.getParentId(), -1);
        }
    }

    // ===== Likes =====

    @Override
    @Transactional
    public Map<String, Object> toggleLike(String userId, String targetType, String targetId, String action) {
        if ("post".equals(targetType)) {
            return togglePostLike(userId, targetId, action);
        } else if ("comment".equals(targetType)) {
            return toggleCommentLike(userId, targetId, action);
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的目标类型: " + targetType);
    }

    @Override
    @Transactional
    public void removeLike(String userId, String targetType, String targetId) {
        if ("post".equals(targetType)) {
            ForumPostLike existing = forumPostLikeMapper.selectOne(
                    new LambdaQueryWrapper<ForumPostLike>()
                            .eq(ForumPostLike::getUserId, userId)
                            .eq(ForumPostLike::getPostId, targetId));
            if (existing != null) {
                forumPostLikeMapper.delete(new LambdaQueryWrapper<ForumPostLike>()
                        .eq(ForumPostLike::getUserId, userId)
                        .eq(ForumPostLike::getPostId, targetId));
                if ("like".equals(existing.getAction())) {
                    forumPostMapper.updateLikeCount(targetId, -1);
                } else {
                    forumPostMapper.updateDislikeCount(targetId, -1);
                }
            }
        } else if ("comment".equals(targetType)) {
            ForumCommentLike existing = forumCommentLikeMapper.selectOne(
                    new LambdaQueryWrapper<ForumCommentLike>()
                            .eq(ForumCommentLike::getUserId, userId)
                            .eq(ForumCommentLike::getCommentId, targetId));
            if (existing != null) {
                forumCommentLikeMapper.delete(new LambdaQueryWrapper<ForumCommentLike>()
                        .eq(ForumCommentLike::getUserId, userId)
                        .eq(ForumCommentLike::getCommentId, targetId));
                if ("like".equals(existing.getAction())) {
                    forumCommentMapper.updateLikeCount(targetId, -1);
                } else {
                    forumCommentMapper.updateDislikeCount(targetId, -1);
                }
            }
        }
    }

    // ===== Favorites =====

    @Override
    @Transactional
    public void addFavorite(String userId, String postId) {
        getActivePost(postId);
        Long count = forumFavoriteMapper.selectCount(
                new LambdaQueryWrapper<ForumFavorite>()
                        .eq(ForumFavorite::getUserId, userId)
                        .eq(ForumFavorite::getPostId, postId));
        if (count > 0) return;
        ForumFavorite fav = new ForumFavorite();
        fav.setUserId(userId);
        fav.setPostId(postId);
        fav.setCreatedAt(LocalDateTime.now());
        forumFavoriteMapper.insert(fav);
        forumPostMapper.updateFavoriteCount(postId, 1);
    }

    @Override
    @Transactional
    public void removeFavorite(String userId, String postId) {
        int deleted = forumFavoriteMapper.delete(new LambdaQueryWrapper<ForumFavorite>()
                .eq(ForumFavorite::getUserId, userId)
                .eq(ForumFavorite::getPostId, postId));
        if (deleted > 0) {
            forumPostMapper.updateFavoriteCount(postId, -1);
        }
    }

    @Override
    public List<PostVO> listFavorites(String userId) {
        List<ForumFavorite> favorites = forumFavoriteMapper.selectList(
                new LambdaQueryWrapper<ForumFavorite>()
                        .eq(ForumFavorite::getUserId, userId)
                        .orderByDesc(ForumFavorite::getCreatedAt));
        if (favorites.isEmpty()) return Collections.emptyList();

        List<String> postIds = favorites.stream().map(ForumFavorite::getPostId).toList();
        List<ForumPost> posts = forumPostMapper.selectList(
                new LambdaQueryWrapper<ForumPost>()
                        .in(ForumPost::getId, postIds)
                        .isNull(ForumPost::getDeletedAt));
        if (posts.isEmpty()) return Collections.emptyList();

        List<String> userIds = posts.stream().map(ForumPost::getUserId).distinct().toList();
        Map<String, User> userMap = batchGetUsers(userIds);
        Map<String, List<String>> tagMap = batchGetTagNames(postIds);

        return posts.stream()
                .map(post -> toPostVO(post, userMap, tagMap, Collections.emptySet(), Collections.emptySet()))
                .toList();
    }

    // ===== Follows =====

    @Override
    @Transactional
    public void followUser(String userId, String targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能关注自己");
        }
        Long count = forumFollowMapper.selectCount(
                new LambdaQueryWrapper<ForumFollow>()
                        .eq(ForumFollow::getFollowerId, userId)
                        .eq(ForumFollow::getFolloweeId, targetUserId));
        if (count > 0) return;
        ForumFollow follow = new ForumFollow();
        follow.setFollowerId(userId);
        follow.setFolloweeId(targetUserId);
        follow.setCreatedAt(LocalDateTime.now());
        forumFollowMapper.insert(follow);
    }

    @Override
    @Transactional
    public void unfollowUser(String userId, String targetUserId) {
        forumFollowMapper.delete(new LambdaQueryWrapper<ForumFollow>()
                .eq(ForumFollow::getFollowerId, userId)
                .eq(ForumFollow::getFolloweeId, targetUserId));
    }

    // ===== Reports =====

    @Override
    @Transactional
    public void report(String userId, String targetType, String targetId, String reason) {
        ForumReport report = new ForumReport();
        report.setReporterId(userId);
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReason(reason);
        report.setStatus("pending");
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());
        forumReportMapper.insert(report);
    }

    // ===== Upload =====

    @Override
    public Map<String, Object> uploadImage(String userId, MultipartFile file) {
        try {
            String url = aliOSSUtil.upload(file, "forum/" + userId + "/images/", true);
            return Map.of("url", url);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "图片上传失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> uploadFile(String userId, MultipartFile file) {
        try {
            String url = aliOSSUtil.upload(file, "forum/" + userId + "/files/", true);
            return Map.of(
                    "url", url,
                    "fileName", file.getOriginalFilename(),
                    "fileSize", file.getSize(),
                    "fileType", file.getContentType()
            );
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件上传失败: " + e.getMessage());
        }
    }

    // ===== My Forum Activity =====

    @Override
    public ForumActivityOverviewVO getMyOverview(String userId) {
        ForumActivityOverviewVO vo = new ForumActivityOverviewVO();

        long postCount = forumPostMapper.selectCount(
                new LambdaQueryWrapper<ForumPost>()
                        .eq(ForumPost::getUserId, userId)
                        .isNull(ForumPost::getDeletedAt));
        long commentCount = forumCommentMapper.selectCount(
                new LambdaQueryWrapper<ForumComment>()
                        .eq(ForumComment::getUserId, userId)
                        .isNull(ForumComment::getDeletedAt));
        long receivedCommentCount = forumCommentMapper.countReceivedComments(userId);
        long postLikeCount = forumPostLikeMapper.countPostLikesReceived(userId);
        long commentLikeCount = forumCommentLikeMapper.countCommentLikesReceived(userId);

        vo.setPostCount(postCount);
        vo.setCommentCount(commentCount);
        vo.setReceivedCommentCount(receivedCommentCount);
        vo.setPostLikeCount(postLikeCount);
        vo.setCommentLikeCount(commentLikeCount);
        vo.setLikeReceivedCount(postLikeCount + commentLikeCount);
        return vo;
    }

    @Override
    public PageResult<PostVO> listMyPosts(String userId, int page, int size) {
        return listPosts(userId, null, null, "latest", "mine", null, page, size);
    }

    @Override
    public PageResult<MyCommentVO> listMyComments(String userId, int page, int size) {
        IPage<Map<String, Object>> result = forumCommentMapper.selectMyComments(
                new Page<>(page, size), userId);
        List<MyCommentVO> records = result.getRecords().stream()
                .map(this::toMyCommentVO)
                .toList();
        return PageResult.<MyCommentVO>builder()
                .records(records)
                .total(result.getTotal())
                .page(page)
                .size(size)
                .build();
    }

    @Override
    public PageResult<ReceivedCommentVO> listReceivedComments(String userId, int page, int size) {
        IPage<Map<String, Object>> result = forumCommentMapper.selectReceivedComments(
                new Page<>(page, size), userId);
        List<ReceivedCommentVO> records = result.getRecords().stream()
                .map(this::toReceivedCommentVO)
                .toList();
        return PageResult.<ReceivedCommentVO>builder()
                .records(records)
                .total(result.getTotal())
                .page(page)
                .size(size)
                .build();
    }

    // ===== Private helpers =====

    private ForumPost getActivePost(String postId) {
        ForumPost post = forumPostMapper.selectById(postId);
        if (post == null || post.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        return post;
    }

    private ForumPost getActivePostForOwner(String userId, String postId) {
        ForumPost post = getActivePost(postId);
        if (!post.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此帖子");
        }
        return post;
    }

    private Map<String, User> batchGetUsers(List<String> userIds) {
        if (userIds.isEmpty()) return Collections.emptyMap();
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    private Map<String, List<String>> batchGetTagNames(List<String> postIds) {
        if (postIds.isEmpty()) return Collections.emptyMap();
        List<ForumPostTag> postTags = forumPostTagMapper.selectList(
                new LambdaQueryWrapper<ForumPostTag>().in(ForumPostTag::getPostId, postIds));
        if (postTags.isEmpty()) return Collections.emptyMap();

        List<String> tagIds = postTags.stream().map(ForumPostTag::getTagId).distinct().toList();
        Map<String, String> tagNameMap = forumTagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(ForumTag::getId, ForumTag::getName, (a, b) -> a));

        Map<String, List<String>> result = new HashMap<>();
        for (ForumPostTag pt : postTags) {
            String tagName = tagNameMap.get(pt.getTagId());
            if (tagName != null) {
                result.computeIfAbsent(pt.getPostId(), k -> new ArrayList<>()).add(tagName);
            }
        }
        return result;
    }

    private Set<String> batchGetLikedPostIds(String userId, List<String> postIds) {
        if (!StringUtils.hasText(userId) || postIds.isEmpty()) return Collections.emptySet();
        return forumPostLikeMapper.selectList(
                        new LambdaQueryWrapper<ForumPostLike>()
                                .eq(ForumPostLike::getUserId, userId)
                                .in(ForumPostLike::getPostId, postIds))
                .stream().map(ForumPostLike::getPostId).collect(Collectors.toSet());
    }

    private Set<String> batchGetFavoritedPostIds(String userId, List<String> postIds) {
        if (!StringUtils.hasText(userId) || postIds.isEmpty()) return Collections.emptySet();
        return forumFavoriteMapper.selectList(
                        new LambdaQueryWrapper<ForumFavorite>()
                                .eq(ForumFavorite::getUserId, userId)
                                .in(ForumFavorite::getPostId, postIds))
                .stream().map(ForumFavorite::getPostId).collect(Collectors.toSet());
    }

    private List<String> getTagNamesForPost(String postId) {
        return batchGetTagNames(Collections.singletonList(postId))
                .getOrDefault(postId, Collections.emptyList());
    }

    private List<ResourceVO> getResourcesForPost(String postId) {
        List<ForumPostResource> refs = forumPostResourceMapper.selectList(
                new LambdaQueryWrapper<ForumPostResource>()
                        .eq(ForumPostResource::getPostId, postId)
                        .orderByAsc(ForumPostResource::getSortOrder));

        List<String> itemIds = refs.stream().map(ForumPostResource::getResourceItemId).toList();
        Map<String, ResourceItem> itemMap = itemIds.isEmpty()
                ? Collections.emptyMap()
                : resourceItemMapper.selectBatchIds(itemIds).stream()
                        .collect(Collectors.toMap(ResourceItem::getId, ri -> ri));

        return refs.stream().map(r -> {
            ResourceVO vo = new ResourceVO();
            vo.setId(r.getId());
            vo.setResourceItemId(r.getResourceItemId());
            vo.setTitle(r.getResourceTitle());
            vo.setType(r.getResourceType());
            vo.setSummary(r.getResourceSummary());
            ResourceItem ri = itemMap.get(r.getResourceItemId());
            if (ri != null) {
                vo.setPackId(ri.getPackId());
            }
            return vo;
        }).toList();
    }

    private List<FileVO> getFilesForPost(String postId) {
        return forumPostFileMapper.selectList(
                        new LambdaQueryWrapper<ForumPostFile>()
                                .eq(ForumPostFile::getPostId, postId)
                                .orderByAsc(ForumPostFile::getSortOrder))
                .stream().map(f -> {
                    FileVO vo = new FileVO();
                    vo.setId(f.getId());
                    vo.setFileName(f.getFileName());
                    vo.setFileUrl(f.getFileUrl());
                    vo.setFileSize(f.getFileSize());
                    vo.setFileType(f.getFileType());
                    return vo;
                }).toList();
    }

    private void savePostTags(String postId, List<String> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return;
        for (String tagId : tagIds) {
            ForumPostTag pt = new ForumPostTag();
            pt.setPostId(postId);
            pt.setTagId(tagId);
            pt.setCreatedAt(LocalDateTime.now());
            forumPostTagMapper.insert(pt);
        }
    }

    private void savePostResources(String postId, List<String> resourceItemIds) {
        if (resourceItemIds == null || resourceItemIds.isEmpty()) return;
        int order = 0;
        for (String itemId : resourceItemIds) {
            ResourceItem ri = resourceItemMapper.selectById(itemId);
            if (ri == null) continue;
            ForumPostResource rpr = new ForumPostResource();
            rpr.setPostId(postId);
            rpr.setResourceItemId(itemId);
            rpr.setResourceTitle(ri.getTitle());
            rpr.setResourceType(ri.getType());
            rpr.setSortOrder(order++);
            rpr.setCreatedAt(LocalDateTime.now());
            forumPostResourceMapper.insert(rpr);
        }
    }

    private void savePostFiles(String postId, List<CreatePostRequest.FileMeta> files) {
        if (files == null || files.isEmpty()) return;
        int order = 0;
        for (CreatePostRequest.FileMeta fm : files) {
            ForumPostFile pf = new ForumPostFile();
            pf.setPostId(postId);
            pf.setFileName(fm.getFileName());
            pf.setFileUrl(fm.getFileUrl());
            pf.setFileSize(fm.getFileSize());
            pf.setFileType(fm.getFileType());
            pf.setSortOrder(order++);
            pf.setCreatedAt(LocalDateTime.now());
            forumPostFileMapper.insert(pf);
        }
    }

    private Map<String, Object> togglePostLike(String userId, String postId, String action) {
        getActivePost(postId);
        ForumPostLike existing = forumPostLikeMapper.selectOne(
                new LambdaQueryWrapper<ForumPostLike>()
                        .eq(ForumPostLike::getUserId, userId)
                        .eq(ForumPostLike::getPostId, postId));

        if (existing != null) {
            if (existing.getAction().equals(action)) {
                forumPostLikeMapper.delete(new LambdaQueryWrapper<ForumPostLike>()
                        .eq(ForumPostLike::getUserId, userId)
                        .eq(ForumPostLike::getPostId, postId));
                if ("like".equals(action)) forumPostMapper.updateLikeCount(postId, -1);
                else forumPostMapper.updateDislikeCount(postId, -1);
                return Map.of("action", "none");
            } else {
                if ("like".equals(existing.getAction())) forumPostMapper.updateLikeCount(postId, -1);
                else forumPostMapper.updateDislikeCount(postId, -1);
                if ("like".equals(action)) forumPostMapper.updateLikeCount(postId, 1);
                else forumPostMapper.updateDislikeCount(postId, 1);
                existing.setAction(action);
                forumPostLikeMapper.update(null, new LambdaUpdateWrapper<ForumPostLike>()
                        .eq(ForumPostLike::getUserId, userId)
                        .eq(ForumPostLike::getPostId, postId)
                        .set(ForumPostLike::getAction, action));
                return Map.of("action", action);
            }
        } else {
            ForumPostLike like = new ForumPostLike();
            like.setUserId(userId);
            like.setPostId(postId);
            like.setAction(action);
            like.setCreatedAt(LocalDateTime.now());
            forumPostLikeMapper.insert(like);
            if ("like".equals(action)) forumPostMapper.updateLikeCount(postId, 1);
            else forumPostMapper.updateDislikeCount(postId, 1);
            return Map.of("action", action);
        }
    }

    private Map<String, Object> toggleCommentLike(String userId, String commentId, String action) {
        ForumComment comment = forumCommentMapper.selectById(commentId);
        if (comment == null || comment.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        ForumCommentLike existing = forumCommentLikeMapper.selectOne(
                new LambdaQueryWrapper<ForumCommentLike>()
                        .eq(ForumCommentLike::getUserId, userId)
                        .eq(ForumCommentLike::getCommentId, commentId));

        if (existing != null) {
            if (existing.getAction().equals(action)) {
                forumCommentLikeMapper.delete(new LambdaQueryWrapper<ForumCommentLike>()
                        .eq(ForumCommentLike::getUserId, userId)
                        .eq(ForumCommentLike::getCommentId, commentId));
                if ("like".equals(action)) forumCommentMapper.updateLikeCount(commentId, -1);
                else forumCommentMapper.updateDislikeCount(commentId, -1);
                return Map.of("action", "none");
            } else {
                if ("like".equals(existing.getAction())) forumCommentMapper.updateLikeCount(commentId, -1);
                else forumCommentMapper.updateDislikeCount(commentId, -1);
                if ("like".equals(action)) forumCommentMapper.updateLikeCount(commentId, 1);
                else forumCommentMapper.updateDislikeCount(commentId, 1);
                existing.setAction(action);
                forumCommentLikeMapper.update(null, new LambdaUpdateWrapper<ForumCommentLike>()
                        .eq(ForumCommentLike::getUserId, userId)
                        .eq(ForumCommentLike::getCommentId, commentId)
                        .set(ForumCommentLike::getAction, action));
                return Map.of("action", action);
            }
        } else {
            ForumCommentLike like = new ForumCommentLike();
            like.setUserId(userId);
            like.setCommentId(commentId);
            like.setAction(action);
            like.setCreatedAt(LocalDateTime.now());
            forumCommentLikeMapper.insert(like);
            if ("like".equals(action)) forumCommentMapper.updateLikeCount(commentId, 1);
            else forumCommentMapper.updateDislikeCount(commentId, 1);
            return Map.of("action", action);
        }
    }

    private MyCommentVO toMyCommentVO(Map<String, Object> map) {
        MyCommentVO vo = new MyCommentVO();
        vo.setId((String) map.get("id"));
        vo.setPostId((String) map.get("postId"));
        vo.setPostTitle((String) map.get("postTitle"));
        vo.setContent((String) map.get("content"));
        vo.setLikeCount(toLong(map.get("likeCount")));
        vo.setReplyCount(toLong(map.get("replyCount")));
        vo.setCreatedAt(formatTime(map.get("createdAt")));
        return vo;
    }

    private ReceivedCommentVO toReceivedCommentVO(Map<String, Object> map) {
        ReceivedCommentVO vo = new ReceivedCommentVO();
        vo.setId((String) map.get("id"));
        vo.setPostId((String) map.get("postId"));
        vo.setPostTitle((String) map.get("postTitle"));
        vo.setContent((String) map.get("content"));
        vo.setCommenterUserId((String) map.get("commenterUserId"));
        vo.setCommenterName((String) map.get("commenterName"));
        vo.setCommenterAvatar((String) map.get("commenterAvatar"));
        vo.setCreatedAt(formatTime(map.get("createdAt")));
        vo.setReply(toLong(map.get("isReply")) == 1);
        return vo;
    }

    private long toLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }

    private String formatTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return formatTime((LocalDateTime) value);
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value)
                .toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().toString();
        return value.toString();
    }

    private PostVO toPostVO(ForumPost post, Map<String, User> userMap,
                            Map<String, List<String>> tagMap,
                            Set<String> likedPostIds, Set<String> favoritedPostIds) {
        PostVO vo = new PostVO();
        fillPostVO(vo, post,
                userMap.get(post.getUserId()),
                tagMap.getOrDefault(post.getId(), Collections.emptyList()),
                likedPostIds.contains(post.getId()) ? "like" : "none",
                favoritedPostIds.contains(post.getId()));
        return vo;
    }

    private void fillPostVO(PostVO vo, ForumPost post, User author,
                            List<String> tags, String userLiked, boolean userFavorited) {
        vo.setId(post.getId());
        vo.setUserId(post.getUserId());
        vo.setUserName(author != null ? author.getDisplayName() : null);
        vo.setUserAvatar(author != null ? author.getAvatarUrl() : null);
        vo.setCourseId(post.getCourseId());
        vo.setTitle(post.getTitle());
        vo.setSummary(post.getSummary());
        vo.setTags(tags);
        vo.setType(post.getType());
        vo.setIsPinned(post.getIsPinned() != null && post.getIsPinned() == 1);
        vo.setIsFeatured(post.getIsFeatured() != null && post.getIsFeatured() == 1);
        vo.setViewCount(post.getViewCount());
        vo.setLikeCount(post.getLikeCount());
        vo.setDislikeCount(post.getDislikeCount());
        vo.setCommentCount(post.getCommentCount());
        vo.setFavoriteCount(post.getFavoriteCount());
        vo.setShareCount(post.getShareCount());
        vo.setUserLiked(userLiked);
        vo.setUserFavorited(userFavorited);
        vo.setLastActivityAt(formatTime(post.getLastActivityAt()));
        vo.setCreatedAt(formatTime(post.getCreatedAt()));
    }

    private CommentVO toCommentVO(ForumComment comment, Map<String, User> userMap,
                                  Map<String, String> commentLikeMap, List<ForumComment> replies) {
        User author = userMap.get(comment.getUserId());
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setPostId(comment.getPostId());
        vo.setParentId(comment.getParentId());
        vo.setRootId(comment.getRootId());
        vo.setUserId(comment.getUserId());
        vo.setUserName(author != null ? author.getDisplayName() : null);
        vo.setUserAvatar(author != null ? author.getAvatarUrl() : null);
        vo.setContent(comment.getContent());
        vo.setLikeCount(comment.getLikeCount());
        vo.setDislikeCount(comment.getDislikeCount());
        vo.setReplyCount(comment.getReplyCount());
        vo.setUserLiked(commentLikeMap.getOrDefault(comment.getId(), "none"));
        vo.setChildren(replies.stream()
                .map(r -> toCommentVO(r, userMap, commentLikeMap, Collections.emptyList()))
                .toList());
        vo.setCreatedAt(formatTime(comment.getCreatedAt()));
        return vo;
    }

    private TagVO toTagVO(ForumTag tag) {
        TagVO vo = new TagVO();
        vo.setId(tag.getId());
        vo.setName(tag.getName());
        vo.setSortOrder(tag.getSortOrder());
        return vo;
    }

    private String generateSummary(String content) {
        if (!StringUtils.hasText(content)) return "";
        String text = content
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("#+\\s*", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("__([^_]+)__", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("_([^_]+)_", "$1")
                .replaceAll("!\\[([^]]*)\\]\\([^)]+\\)", "$1")
                .replaceAll("\\[([^]]*)\\]\\([^)]+\\)", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("^>\\s*", "")
                .replaceAll("\\n>\\s*", "\n")
                .replaceAll("^[-*+]\\s+", "")
                .replaceAll("\\n[-*+]\\s+", "\n")
                .replaceAll("\\n\\d+\\.\\s+", "\n")
                .replaceAll("\\n+", " ")
                .trim();
        if (text.length() > 200) {
            text = text.substring(0, 200) + "...";
        }
        return text;
    }

    private String formatTime(LocalDateTime time) {
        return time != null ? time.atZone(ZoneId.systemDefault()).toInstant().toString() : null;
    }
}
