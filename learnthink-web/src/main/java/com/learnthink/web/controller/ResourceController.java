package com.learnthink.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.result.Result;
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
            }
        } catch (Exception e) {
            dto.put("sources", List.of());
            dto.put("sourcesCount", 0);
            dto.put("qualityScore", 75);
        }

        return dto;
    }
}
