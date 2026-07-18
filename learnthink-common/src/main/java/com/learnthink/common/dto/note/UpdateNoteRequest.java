package com.learnthink.common.dto.note;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateNoteRequest {
    @NotBlank(message = "笔记内容不能为空")
    private String content;
}
