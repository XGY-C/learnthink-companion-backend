package com.learnthink.common.dto.forum;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class PostDetailVO extends PostVO {
    private String content;
    private List<ResourceVO> resources;
    private List<FileVO> files;
    private Boolean userFollowed;
}
