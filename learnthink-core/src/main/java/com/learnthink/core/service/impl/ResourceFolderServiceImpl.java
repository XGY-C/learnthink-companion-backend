package com.learnthink.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.exception.ErrorCode;
import com.learnthink.core.domain.entity.ResourceFolder;
import com.learnthink.core.domain.entity.ResourceItem;
import com.learnthink.core.repository.ResourceFolderMapper;
import com.learnthink.core.repository.ResourceItemMapper;
import com.learnthink.core.service.ResourceFolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceFolderServiceImpl implements ResourceFolderService {

    private final ResourceFolderMapper folderMapper;
    private final ResourceItemMapper resourceItemMapper;

    @Override
    public List<Map<String, Object>> getTree(String userId, String courseId) {
        List<ResourceFolder> all = folderMapper.selectList(
            new LambdaQueryWrapper<ResourceFolder>()
                .eq(ResourceFolder::getUserId, userId)
                .eq(ResourceFolder::getCourseId, courseId)
                .isNull(ResourceFolder::getDeletedAt)
                .orderByAsc(ResourceFolder::getSortOrder)
                .orderByAsc(ResourceFolder::getCreatedAt));

        // Count resources per folder
        Map<String, Long> countMap = resourceItemMapper.selectList(
            new LambdaQueryWrapper<ResourceItem>()
                .eq(ResourceItem::getUserId, userId)
                .eq(ResourceItem::getCourseId, courseId)
                .isNull(ResourceItem::getDeletedAt)
                .isNotNull(ResourceItem::getFolderId))
            .stream()
            .filter(i -> i.getFolderId() != null)
            .collect(Collectors.groupingBy(ResourceItem::getFolderId, Collectors.counting()));

        Map<String, List<ResourceFolder>> childrenMap = all.stream()
            .filter(f -> f.getParentId() != null)
            .collect(Collectors.groupingBy(ResourceFolder::getParentId, Collectors.toList()));

        List<Map<String, Object>> roots = new ArrayList<>();
        for (ResourceFolder f : all) {
            if (f.getParentId() == null) {
                roots.add(buildNode(f, childrenMap, countMap));
            }
        }
        return roots;
    }

    private Map<String, Object> buildNode(ResourceFolder folder, Map<String, List<ResourceFolder>> childrenMap,
                                           Map<String, Long> countMap) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", folder.getId());
        node.put("name", folder.getName());
        node.put("parentId", folder.getParentId());
        node.put("sortOrder", folder.getSortOrder() != null ? folder.getSortOrder() : 0);
        node.put("resourceCount", countMap.getOrDefault(folder.getId(), 0L).intValue());

        List<Map<String, Object>> childNodes = new ArrayList<>();
        List<ResourceFolder> kids = childrenMap.getOrDefault(folder.getId(), Collections.emptyList());
        for (ResourceFolder kid : kids) {
            childNodes.add(buildNode(kid, childrenMap, countMap));
        }
        node.put("children", childNodes);
        return node;
    }

    @Override
    @Transactional
    public Map<String, Object> create(String userId, String courseId, String parentId, String name) {
        ResourceFolder folder = new ResourceFolder();
        folder.setUserId(userId);
        folder.setCourseId(courseId);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setSortOrder(0);
        folderMapper.insert(folder);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", folder.getId());
        result.put("name", folder.getName());
        result.put("parentId", folder.getParentId());
        result.put("sortOrder", folder.getSortOrder());
        result.put("resourceCount", 0);
        result.put("children", Collections.emptyList());
        return result;
    }

    @Override
    @Transactional
    public void update(String id, String userId, String name, String parentId, Integer sortOrder) {
        ResourceFolder folder = folderMapper.selectById(id);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件夹不存在或无权限修改");
        }
        if (name != null) folder.setName(name);
        if (parentId != null) folder.setParentId(parentId);
        if (sortOrder != null) folder.setSortOrder(sortOrder);
        folderMapper.updateById(folder);
    }

    @Override
    @Transactional
    public void softDelete(String id, String userId) {
        ResourceFolder folder = folderMapper.selectById(id);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件夹不存在或无权限删除");
        }

        // 1. Move all resources in this folder to unarchived (folder_id = NULL)
        List<ResourceItem> items = resourceItemMapper.selectList(
            new LambdaQueryWrapper<ResourceItem>()
                .eq(ResourceItem::getFolderId, id));
        for (ResourceItem item : items) {
            item.setFolderId(null);
            resourceItemMapper.updateById(item);
        }

        // 2. Recursively soft-delete all sub-folders
        softDeleteChildrenRecursively(id);

        // 3. Soft-delete the folder itself
        folder.setDeletedAt(LocalDateTime.now());
        folderMapper.updateById(folder);
    }

    private void softDeleteChildrenRecursively(String parentId) {
        List<ResourceFolder> children = folderMapper.selectList(
            new LambdaQueryWrapper<ResourceFolder>()
                .eq(ResourceFolder::getParentId, parentId)
                .isNull(ResourceFolder::getDeletedAt));
        for (ResourceFolder child : children) {
            softDeleteChildrenRecursively(child.getId());
            // Move resources in child folder to unarchived
            List<ResourceItem> childItems = resourceItemMapper.selectList(
                new LambdaQueryWrapper<ResourceItem>()
                    .eq(ResourceItem::getFolderId, child.getId()));
            for (ResourceItem item : childItems) {
                item.setFolderId(null);
                resourceItemMapper.updateById(item);
            }
            child.setDeletedAt(LocalDateTime.now());
            folderMapper.updateById(child);
        }
    }
}
