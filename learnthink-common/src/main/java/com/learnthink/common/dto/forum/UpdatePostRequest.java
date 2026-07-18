package com.learnthink.common.dto.forum;

import lombok.Data;

import java.util.List;

@Data
public class UpdatePostRequest {
    private String title;
    private String content;
    private List<String> tagIds;
    private String type;
    private List<String> resourceItemIds;
    private List<CreatePostRequest.FileMeta> files;
}
