package com.learnthink.common.dto.forum;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCommentRequest {
    @NotBlank(message = "评论内容不能为空")
    private String content;

    private String parentId;
    private String rootId;
}
