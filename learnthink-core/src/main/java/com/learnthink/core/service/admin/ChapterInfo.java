package com.learnthink.core.service.admin;

import lombok.Data;

@Data
public class ChapterInfo {
    private String title;
    private String content;
    private String ossUrl;

    public ChapterInfo(String title, String content) {
        this.title = title;
        this.content = content;
    }
}
