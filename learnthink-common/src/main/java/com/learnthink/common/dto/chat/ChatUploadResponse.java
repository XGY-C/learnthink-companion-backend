package com.learnthink.common.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatUploadResponse {
    private String url;
    private String fileName;
    private long fileSize;
    private String contentType;
    private String parsedText;
    @JsonProperty("isImage")
    private boolean isImage;
}
