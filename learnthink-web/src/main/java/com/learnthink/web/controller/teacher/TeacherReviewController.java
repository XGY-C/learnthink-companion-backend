package com.learnthink.web.controller.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.result.Result;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.domain.entity.ReviewRecord;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.repository.ResourcePackMapper;
import com.learnthink.core.repository.ReviewRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/teacher/courses/{courseId}/review")
@RequiredArgsConstructor
public class TeacherReviewController {

    private final ResourceItemMapper resourceItemMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final ReviewRecordMapper reviewRecordMapper;

    @GetMapping("/items")
    public Result<List<Map<String, Object>>> listReviewItems(@PathVariable String courseId) {
        List<ResourcePack> packs = resourcePackMapper.selectList(
                new LambdaQueryWrapper<ResourcePack>()
                        .eq(ResourcePack::getCourseId, courseId)
        );
        if (packs.isEmpty()) return Result.success(List.of());

        Set<String> packIds = packs.stream().map(ResourcePack::getId).collect(Collectors.toSet());

        List<ResourceItem> items = resourceItemMapper.selectList(
                new LambdaQueryWrapper<ResourceItem>()
                        .in(ResourceItem::getPackId, packIds)
                        .eq(ResourceItem::getReviewStatus, "pending")
                        .orderByDesc(ResourceItem::getCreatedAt)
        );

        Map<String, String> packCourseMap = packs.stream()
                .collect(Collectors.toMap(ResourcePack::getId, ResourcePack::getCourseId));

        List<Map<String, Object>> result = items.stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.getId());
            m.put("packId", item.getPackId());
            m.put("type", item.getType());
            m.put("title", item.getTitle());
            m.put("status", item.getStatus());
            m.put("confidence", item.getConfidence());
            m.put("qualityScore", item.getQualityScore());
            m.put("reviewStatus", item.getReviewStatus());
            m.put("reviewSummary", item.getReviewSummary());
            m.put("contentRef", item.getContentRef());
            m.put("sourcesJson", item.getSourcesJson());
            m.put("courseId", packCourseMap.getOrDefault(item.getPackId(), courseId));
            m.put("createdAt", item.getCreatedAt() != null ? item.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toString() : null);
            return m;
        }).collect(Collectors.toList());

        return Result.success(result);
    }

    @GetMapping("/counts")
    public Result<Map<String, Integer>> getReviewCounts(@PathVariable String courseId) {
        List<ResourcePack> packs = resourcePackMapper.selectList(
                new LambdaQueryWrapper<ResourcePack>()
                        .eq(ResourcePack::getCourseId, courseId)
        );
        if (packs.isEmpty()) return Result.success(Map.of("pending", 0));

        Set<String> packIds = packs.stream().map(ResourcePack::getId).collect(Collectors.toSet());

        int pending = resourceItemMapper.selectCount(
                new LambdaQueryWrapper<ResourceItem>()
                        .in(ResourceItem::getPackId, packIds)
                        .eq(ResourceItem::getReviewStatus, "pending")
        ).intValue();

        return Result.success(Map.of("pending", pending));
    }

    @PostMapping("/{itemId}")
    public Result<Void> review(@PathVariable String courseId, @PathVariable String itemId,
                                @RequestBody Map<String, Object> body) {
        String result = (String) body.get("result");
        String note = (String) body.getOrDefault("note", "");

        ResourceItem item = resourceItemMapper.selectById(itemId);
        if (item == null) return Result.error(404, "资源不存在");

        item.setReviewStatus(result);
        item.setReviewSummary(note);
        item.setUpdatedAt(LocalDateTime.now());
        resourceItemMapper.updateById(item);

        ReviewRecord record = new ReviewRecord();
        record.setResourceItemId(itemId);
        record.setResourcePackId(item.getPackId());
        record.setTaskId(item.getTaskId());
        record.setResult(result);
        record.setReasonsJson(note);
        record.setCitationCoverage(BigDecimal.ZERO);
        record.setCreatedAt(LocalDateTime.now());
        reviewRecordMapper.insert(record);

        return Result.success(null, "已" + ("approved".equals(result) ? "通过" : "驳回"));
    }

    @PostMapping("/{itemId}/retry")
    public Result<Void> retryReview(@PathVariable String courseId, @PathVariable String itemId) {
        ResourceItem item = resourceItemMapper.selectById(itemId);
        if (item == null) return Result.error(404, "资源不存在");

        item.setReviewStatus("pending");
        item.setStatus("pending");
        item.setUpdatedAt(LocalDateTime.now());
        resourceItemMapper.updateById(item);

        return Result.success(null, "已退回重新生成");
    }
}
