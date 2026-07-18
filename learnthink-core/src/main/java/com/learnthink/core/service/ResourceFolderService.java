package com.learnthink.core.service;

import java.util.List;
import java.util.Map;

public interface ResourceFolderService {
    List<Map<String, Object>> getTree(String userId, String courseId);
    Map<String, Object> create(String userId, String courseId, String parentId, String name);
    void update(String id, String userId, String name, String parentId, Integer sortOrder);
    void softDelete(String id, String userId);
}
