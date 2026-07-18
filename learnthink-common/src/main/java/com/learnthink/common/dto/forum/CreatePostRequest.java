package com.learnthink.common.dto.forum;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreatePostRequest {
    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    private String courseId;

    private List<String> tagIds;
    private String type;
    private List<String> resourceItemIds;
    private List<FileMeta> files;

    @Data
    public static class FileMeta {
        private String fileName;
        private String fileUrl;
        private Long fileSize;
        private String fileType;
    }
}
