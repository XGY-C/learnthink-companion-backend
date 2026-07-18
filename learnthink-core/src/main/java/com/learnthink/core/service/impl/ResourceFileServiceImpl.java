package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.common.dto.resource.ResourceFileDTO;
import com.learnthink.common.dto.resource.ResourceFilePageDTO;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.core.domain.entity.LearningRecord;
import com.learnthink.core.domain.entity.Note;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.domain.entity.ResourcePack;
import com.learnthink.core.repository.*;
import com.learnthink.core.service.ResourceFileService;
import com.learnthink.core.service.VideoRenderPoller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceFileServiceImpl implements ResourceFileService {

    private final ResourceItemMapper resourceItemMapper;
    private final ResourcePackMapper resourcePackMapper;
    private final NoteMapper noteMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final ObjectMapper objectMapper;
    private final VideoRenderPoller videoRenderPoller;

    @Override
    public ResourceFilePageDTO listFiles(String userId, String courseId, String folderId,
                                          String type, String confidence, String sort,
                                          int page, int size) {
        int folderMode;
        String folderParam = null;
        if (folderId == null) {
            folderMode = 0;
        } else if ("all".equals(folderId)) {
            folderMode = 1;
        } else {
            folderMode = 2;
            folderParam = folderId;
        }

        int sortType;
        boolean forceIndex;
        if ("title".equals(sort)) {
            sortType = 1;
            forceIndex = false;
        } else if ("quality".equals(sort)) {
            sortType = 2;
            forceIndex = false;
        } else {
            sortType = 0;
            forceIndex = true;
        }

        String typeParam = StringUtils.hasText(type) ? type : null;
        String confParam = StringUtils.hasText(confidence) ? confidence : null;

        if (page < 1) page = 1;
        if (size < 1) size = 50;

        long offset = (long) (page - 1) * size;
        List<ResourceItem> records = resourceItemMapper.selectListFiles(
                userId, courseId, folderMode, folderParam, typeParam, confParam,
                forceIndex, sortType, offset, size);
        long total = resourceItemMapper.countListFiles(
                userId, courseId, folderMode, folderParam, typeParam, confParam);

        // Batch fetch pack topics
        Set<String> packIds = records.stream()
            .map(ResourceItem::getPackId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<String, String> topicMap = new HashMap<>();
        if (!packIds.isEmpty()) {
            List<ResourcePack> packs = resourcePackMapper.selectBatchIds(new ArrayList<>(packIds));
            for (ResourcePack pack : packs) {
                topicMap.put(pack.getId(), pack.getTopic());
            }
        }

        // Batch fetch note counts and learning status per resource_item_id
        List<String> allItemIds = records.stream().map(ResourceItem::getId).toList();
        Map<String, Long> finalNoteCountMap = new HashMap<>();
        Set<String> finalLearningItemIds = new HashSet<>();
        if (!allItemIds.isEmpty()) {
            List<Note> notes = noteMapper.selectList(
                new LambdaQueryWrapper<Note>()
                    .in(Note::getResourceItemId, allItemIds)
                    .isNull(Note::getDeletedAt));
            finalNoteCountMap = notes.stream()
                .filter(n -> n.getResourceItemId() != null)
                .collect(Collectors.groupingBy(Note::getResourceItemId, Collectors.counting()));

            List<LearningRecord> lrs = learningRecordMapper.selectList(
                new LambdaQueryWrapper<LearningRecord>()
                    .eq(LearningRecord::getUserId, userId)
                    .in(LearningRecord::getResourceItemId, allItemIds)
                    .isNotNull(LearningRecord::getResourceItemId));
            for (LearningRecord lr : lrs) {
                if ("completed".equals(lr.getStatus()) || "in_progress".equals(lr.getStatus())) {
                    finalLearningItemIds.add(lr.getResourceItemId());
                }
            }
        }

        Map<String, Long> noteCountMap = finalNoteCountMap;
        Set<String> learningItemIds = finalLearningItemIds;
        List<ResourceFileDTO> items = records.stream()
            .map(item -> toDTO(item, topicMap.get(item.getPackId()),
                noteCountMap.getOrDefault(item.getId(), 0L).intValue(),
                learningItemIds.contains(item.getId()), false))
            .toList();

        ResourceFilePageDTO pageDTO = new ResourceFilePageDTO();
        pageDTO.setItems(items);
        pageDTO.setTotal(total);
        pageDTO.setPage(page);
        pageDTO.setSize(size);
        return pageDTO;
    }

    @Override
    public ResourceFileDTO getFileDetail(String id) {
        ResourceItem item = resourceItemMapper.selectById(id);
        if (item == null || item.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }

        // Resolve video URL if needed
        videoRenderPoller.resolveVideoUrl(item);
        item = resourceItemMapper.selectById(id);

        String topic = null;
        if (item.getPackId() != null) {
            ResourcePack pack = resourcePackMapper.selectById(item.getPackId());
            if (pack != null) {
                topic = pack.getTopic();
            }
        }

        // Count notes for this resource item
        int noteCount = 0;
        if (item.getId() != null) {
            Long cnt = noteMapper.selectCount(
                new LambdaQueryWrapper<Note>()
                    .eq(Note::getResourceItemId, item.getId())
                    .isNull(Note::getDeletedAt));
            noteCount = cnt != null ? cnt.intValue() : 0;
        }

        // Check learning status
        boolean isLearning = false;
        if (item.getId() != null) {
            Long lrCnt = learningRecordMapper.selectCount(
                new LambdaQueryWrapper<LearningRecord>()
                    .eq(LearningRecord::getResourceItemId, item.getId()));
            isLearning = lrCnt != null && lrCnt > 0;
        }

        return toDTO(item, topic, noteCount, isLearning, true);
    }

    @Override
    public List<ResourceFileDTO> searchFiles(String userId, String courseId, String query, int page, int size) {
        if (!StringUtils.hasText(query)) return List.of();

        // Search by title (LIKE)
        LambdaQueryWrapper<ResourceItem> wrapper = new LambdaQueryWrapper<ResourceItem>()
            .eq(ResourceItem::getUserId, userId)
            .eq(ResourceItem::getCourseId, courseId)
            .isNull(ResourceItem::getDeletedAt)
            .like(ResourceItem::getTitle, query)
            .orderByDesc(ResourceItem::getCreatedAt);

        Page<ResourceItem> p = new Page<>(page, size);
        List<ResourceItem> items = resourceItemMapper.selectPage(p, wrapper).getRecords();

        // Also try to match by pack topic
        if (items.isEmpty()) {
            List<ResourcePack> packs = resourcePackMapper.selectList(
                new LambdaQueryWrapper<ResourcePack>()
                    .eq(ResourcePack::getUserId, userId)
                    .eq(ResourcePack::getCourseId, courseId)
                    .like(ResourcePack::getTopic, query)
                    .isNull(ResourcePack::getDeletedAt));
            if (!packs.isEmpty()) {
                Set<String> packIds = packs.stream().map(ResourcePack::getId).collect(Collectors.toSet());
                items = resourceItemMapper.selectList(
                    new LambdaQueryWrapper<ResourceItem>()
                        .eq(ResourceItem::getUserId, userId)
                        .eq(ResourceItem::getCourseId, courseId)
                        .isNull(ResourceItem::getDeletedAt)
                        .in(ResourceItem::getPackId, packIds)
                        .orderByDesc(ResourceItem::getCreatedAt));
            }
        }

        return items.stream()
            .map(item -> {
                String t = null;
                if (item.getPackId() != null) {
                    ResourcePack pack = resourcePackMapper.selectById(item.getPackId());
                    if (pack != null) t = pack.getTopic();
                }
                return toDTO(item, t, 0, false, false);
            })
            .toList();
    }

    @Override
    @Transactional
    public void moveFile(String id, String userId, String folderId) {
        ResourceItem item = resourceItemMapper.selectById(id);
        if (item == null || item.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        item.setFolderId(folderId);
        resourceItemMapper.updateById(item);
    }

    @Override
    @Transactional
    public void softDelete(String id, String userId) {
        ResourceItem item = resourceItemMapper.selectById(id);
        if (item == null || item.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        item.setDeletedAt(LocalDateTime.now());
        resourceItemMapper.updateById(item);
    }

    @Override
    @Transactional
    public void regenerate(String id, String userId) {
        ResourceItem item = resourceItemMapper.selectById(id);
        if (item == null || item.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        item.setStatus("pending");
        item.setReviewStatus("pending");
        resourceItemMapper.updateById(item);
    }

    @SuppressWarnings("unchecked")
    private ResourceFileDTO toDTO(ResourceItem item, String topic, int noteCount, boolean isLearning, boolean withContent) {
        ResourceFileDTO dto = new ResourceFileDTO();
        dto.setId(item.getId());
        dto.setType(item.getType());
        dto.setTitle(item.getTitle());
        dto.setTopic(topic);
        dto.setFolderId(item.getFolderId());
        dto.setPackId(item.getPackId());
        dto.setConfidence(item.getConfidence());
        dto.setStatus(item.getStatus());
        dto.setNoteCount(noteCount);
        dto.setIsLearning(isLearning);
        dto.setCreatedAt(item.getCreatedAt() != null
            ? item.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
        dto.setUpdatedAt(item.getUpdatedAt() != null
            ? item.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);

        int qualityScore = 75;
        try {
            if (item.getMetadataJson() != null) {
                Map<String, Object> meta = objectMapper.readValue(item.getMetadataJson(),
                    new TypeReference<Map<String, Object>>() {});
                Object score = meta.getOrDefault("quality_score", 75);
                qualityScore = score instanceof Number ? ((Number) score).intValue() : 75;

                if (withContent && meta.containsKey("content")) {
                    Object contentObj = meta.get("content");
                    dto.setContent(contentObj instanceof String ? (String) contentObj
                        : objectMapper.writeValueAsString(contentObj));
                }
            }
        } catch (Exception ignored) {}
        dto.setQualityScore(qualityScore);

        if (withContent) {
            try {
                if (item.getSourcesJson() != null) {
                    List<Map<String, Object>> sources = objectMapper.readValue(item.getSourcesJson(),
                        new TypeReference<List<Map<String, Object>>>() {});
                    dto.setSources(sources);
                    dto.setSourcesCount(sources.size());
                }
            } catch (Exception e) {
                dto.setSources(List.of());
                dto.setSourcesCount(0);
            }
        }

        return dto;
    }
}
