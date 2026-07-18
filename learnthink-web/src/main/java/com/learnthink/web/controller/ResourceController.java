package com.learnthink.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.resource.MoveResourceRequest;
import com.learnthink.common.dto.resource.ResourceFileDTO;
import com.learnthink.common.dto.resource.ResourceFilePageDTO;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.service.ResourceFileService;
import com.learnthink.core.service.VideoRenderPoller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceController {

    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ObjectMapper objectMapper;
    private final VideoRenderPoller videoRenderPoller;
    private final ResourceFileService resourceFileService;

    // ============================================================
    // 包级接口（保留，给 Studio 页用）
    // ============================================================

    @GetMapping("/resources/packs")
    public Result<List<Map<String, Object>>> listPacks(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        List<ResourcePack> packs = resourcePackMapper.selectList(
            new LambdaQueryWrapper<ResourcePack>()
                .eq(ResourcePack::getUserId, userId)
                .eq(ResourcePack::getCourseId, courseId)
                .ge(ResourcePack::getCreatedAt, threeDaysAgo)
                .orderByDesc(ResourcePack::getCreatedAt));
        return Result.success(packs.stream().map(p -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("pack_id", p.getId());
            dto.put("topic", p.getTopic());
            dto.put("created_at", p.getCreatedAt() != null
                ? p.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
            dto.put("task_id", p.getTaskId());
            dto.put("profile_version_id", p.getGeneratedFromProfileVersionId());

            List<ResourceItem> items = resourceItemMapper.selectList(
                new LambdaQueryWrapper<ResourceItem>().eq(ResourceItem::getPackId, p.getId()));
            dto.put("resourceCount", items.size());
            dto.put("resourceTypes", items.stream()
                .map(ResourceItem::getType).distinct().toList());
            dto.put("avgQuality", items.stream()
                .mapToInt(i -> toQualityScore(i))
                .average().orElse(0));
            dto.put("avgConfidence", computeAvgConfidence(items));
            dto.put("estimatedMinutes", items.size() * 10);

            return dto;
        }).toList());
    }

    @GetMapping("/resource-packs/{packId}")
    public Result<Map<String, Object>> getPack(@PathVariable String packId) {
        ResourcePack pack = resourcePackMapper.selectById(packId);
        if (pack == null) {
            return Result.error("PACK_NOT_FOUND");
        }

        List<ResourceItem> items = resourceItemMapper.selectList(
            new LambdaQueryWrapper<ResourceItem>().eq(ResourceItem::getPackId, packId));

        for (ResourceItem item : items) {
            log.info("[ResourceLoad] getPack packId={} itemId={} type={} subtopic_index={} title={}",
                packId, item.getId(), item.getType(), item.getSubtopicIndex(), item.getTitle());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("pack_id", pack.getId());
        data.put("topic", pack.getTopic());
        data.put("created_at", pack.getCreatedAt() != null
            ? pack.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
        data.put("push_reason_json", pack.getPushReasonJson());
        data.put("resources", items.stream().map(this::toResourceDto).toList());
        return Result.success(data);
    }

    // ============================================================
    // 文件级接口（资源库用）
    // ============================================================

    @GetMapping("/resources")
    public Result<ResourceFilePageDTO> listFiles(
            @RequestParam String courseId,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String confidence,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(resourceFileService.listFiles(userId, courseId, folderId,
            type, confidence, sort, page, size));
    }

    @GetMapping("/resources/{id}")
    public Result<ResourceFileDTO> getFile(@PathVariable String id) {
        return Result.success(resourceFileService.getFileDetail(id));
    }

    @GetMapping("/resources/search")
    public Result<List<ResourceFileDTO>> searchFiles(
            @RequestParam String courseId,
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = UserContextUtil.getCurrentUserId();
        return Result.success(resourceFileService.searchFiles(userId, courseId, q, page, size));
    }

    @PutMapping("/resources/{id}/move")
    public Result<Void> moveFile(@PathVariable String id, @RequestBody MoveResourceRequest req) {
        String userId = UserContextUtil.getCurrentUserId();
        resourceFileService.moveFile(id, userId, req.getFolderId());
        return Result.success();
    }

    @DeleteMapping("/resources/{id}")
    public Result<Void> deleteFile(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        resourceFileService.softDelete(id, userId);
        return Result.success();
    }

    @PostMapping("/resources/{id}/regenerate")
    public Result<Map<String, Object>> regenerateFile(@PathVariable String id) {
        String userId = UserContextUtil.getCurrentUserId();
        resourceFileService.regenerate(id, userId);
        log.info("Resource regeneration requested: resourceId={}", id);
        return Result.success(Map.of("resource_id", id, "status", "pending"));
    }

    // ============================================================
    // 内部辅助方法
    // ============================================================

    private int toQualityScore(ResourceItem item) {
        try {
            if (item.getMetadataJson() != null) {
                Map<String, Object> meta = objectMapper.readValue(item.getMetadataJson(),
                    new TypeReference<Map<String, Object>>() {});
                Object score = meta.getOrDefault("quality_score", 75);
                return score instanceof Number ? ((Number) score).intValue() : 75;
            }
        } catch (Exception ignored) {}
        return 75;
    }

    private String computeAvgConfidence(List<ResourceItem> items) {
        if (items.isEmpty()) return "medium";
        long high = items.stream().filter(i -> "high".equals(i.getConfidence())).count();
        long low = items.stream().filter(i -> "low".equals(i.getConfidence())).count();
        double ratio = (double) high / items.size();
        if (ratio >= 0.6) return "high";
        if ((double) low / items.size() >= 0.5) return "low";
        return "medium";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toResourceDto(ResourceItem item) {
        videoRenderPoller.resolveVideoUrl(item);
        item = resourceItemMapper.selectById(item.getId());

        Map<String, Object> dto = new HashMap<>();
        dto.put("id", item.getId());
        dto.put("type", item.getType());
        dto.put("title", item.getTitle());
        dto.put("status", item.getStatus());
        dto.put("confidence", item.getConfidence());
        dto.put("review_status", item.getReviewStatus());
        dto.put("subtopic_index", item.getSubtopicIndex());

        try {
            if (item.getSourcesJson() != null) {
                dto.put("sources", objectMapper.readValue(item.getSourcesJson(), List.class));
                dto.put("sourcesCount", ((List<?>) dto.get("sources")).size());
            }
            if (item.getMetadataJson() != null) {
                Map<String, Object> meta = objectMapper.readValue(item.getMetadataJson(),
                    new TypeReference<Map<String, Object>>() {});
                dto.put("qualityScore", meta.getOrDefault("quality_score", 75));
                if (meta.containsKey("content")) {
                    Object contentObj = meta.get("content");
                    dto.put("content", contentObj);
                    if ("video".equals(item.getType()) && contentObj instanceof String) {
                        try {
                            Map<String, Object> inner = objectMapper.readValue((String) contentObj,
                                new TypeReference<Map<String, Object>>() {});
                            if (inner.containsKey("videoUrl")) {
                                dto.put("videoUrl", inner.get("videoUrl"));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            dto.put("sources", List.of());
            dto.put("sourcesCount", 0);
            dto.put("qualityScore", 75);
        }

        return dto;
    }
}
