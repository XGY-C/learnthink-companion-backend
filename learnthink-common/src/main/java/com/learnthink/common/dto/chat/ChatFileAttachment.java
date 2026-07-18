package com.learnthink.common.dto.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatFileAttachment {
    private String fileName;
    private String fileUrl;
    private String parsedText;
    private long fileSize;
    private String contentType;
    @JsonProperty("isImage")
    private boolean isImage;
}
