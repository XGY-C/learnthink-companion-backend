package com.learnthink.common.dto.resource;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ResourceFileDTO {
    private String id;
    private String type;
    private String title;
    private String topic;
    private String folderId;
    private String packId;
    private String confidence;
    private Integer qualityScore;
    private String status;
    private Integer noteCount;
    private Boolean isLearning;
    private String createdAt;
    private String updatedAt;

    private String content;
    private List<Map<String, Object>> sources;
    private Integer sourcesCount;
}
