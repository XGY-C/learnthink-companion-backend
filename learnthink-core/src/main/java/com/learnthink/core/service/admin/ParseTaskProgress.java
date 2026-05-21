package com.learnthink.core.service.admin;

import lombok.Data;
import java.util.List;

@Data
public class ParseTaskProgress {
    private String taskId;
    private String state;       // pending | running | converting | downloading | extracting | splitting | completed | failed
    private int progress;       // 0-100
    private String message;
    private String error;
    private List<ChapterInfo> chapters;
    private String fullMdUrl;   // 完整MD的OSS访问URL（仅completed时有效）
}
