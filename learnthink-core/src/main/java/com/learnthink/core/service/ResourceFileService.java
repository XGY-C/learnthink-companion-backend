package com.learnthink.core.service;

import com.learnthink.common.dto.resource.ResourceFileDTO;
import com.learnthink.common.dto.resource.ResourceFilePageDTO;

import java.util.List;

public interface ResourceFileService {
    ResourceFilePageDTO listFiles(String userId, String courseId, String folderId,
                                  String type, String confidence, String sort,
                                  int page, int size);
    ResourceFileDTO getFileDetail(String id);
    List<ResourceFileDTO> searchFiles(String userId, String courseId, String query, int page, int size);
    void moveFile(String id, String userId, String folderId);
    void softDelete(String id, String userId);
    void regenerate(String id, String userId);
}
