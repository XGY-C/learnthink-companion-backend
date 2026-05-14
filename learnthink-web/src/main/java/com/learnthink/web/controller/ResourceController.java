package com.learnthink.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceController {

    private final ResourcePackMapper resourcePackMapper;
    private final ResourceItemMapper resourceItemMapper;
    private final ObjectMapper objectMapper;

    @GetMapping("/resources/packs")
    public Result<List<Map<String, Object>>> listPacks(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        List<ResourcePack> packs = resourcePackMapper.selectList(
            new LambdaQueryWrapper<ResourcePack>()
                .eq(ResourcePack::getUserId, userId)
                .eq(ResourcePack::getCourseId, courseId)
                .orderByDesc(ResourcePack::getCreatedAt));
        return Result.success(packs.stream().map(p -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("pack_id", p.getId());
            dto.put("topic", p.getTopic());
            dto.put("created_at", p.getCreatedAt());
            dto.put("task_id", p.getTaskId());
            dto.put("profile_version_id", p.getGeneratedFromProfileVersionId());
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

        Map<String, Object> data = new HashMap<>();
        data.put("pack_id", pack.getId());
        data.put("topic", pack.getTopic());
        data.put("push_reason_json", pack.getPushReasonJson());
        data.put("resources", items.stream().map(this::toResourceDto).toList());
        return Result.success(data);
    }

    @PostMapping("/resources/regenerate")
    public Result<Map<String, Object>> regenerate(@RequestBody Map<String, String> req) {
        String resourceId = req.get("resource_id");
        String taskId = req.get("task_id");

        ResourceItem item = resourceItemMapper.selectById(resourceId);
        if (item == null) {
            return Result.error("RESOURCE_NOT_FOUND");
        }

        // Reset status to pending for regeneration
        item.setStatus("pending");
        item.setReviewStatus("pending");
        resourceItemMapper.updateById(item);

        log.info("Resource regeneration requested: resourceId={}, taskId={}", resourceId, taskId);
        return Result.success(Map.of("resource_id", resourceId, "task_id", taskId, "status", "pending"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toResourceDto(ResourceItem item) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", item.getId());
        dto.put("type", item.getType());
        dto.put("title", item.getTitle());
        dto.put("status", item.getStatus());
        dto.put("confidence", item.getConfidence());
        dto.put("review_status", item.getReviewStatus());

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
                    dto.put("content", meta.get("content"));
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
