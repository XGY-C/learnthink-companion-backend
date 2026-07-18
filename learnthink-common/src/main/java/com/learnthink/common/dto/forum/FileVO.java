package com.learnthink.common.dto.forum;

import lombok.Data;

@Data
public class FileVO {
    private String id;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String fileType;
}
