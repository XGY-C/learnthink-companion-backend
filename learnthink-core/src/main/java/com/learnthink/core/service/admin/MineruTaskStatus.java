package com.learnthink.core.service.admin;

import lombok.Data;

@Data
public class MineruTaskStatus {
    private String state;
    private int progress;
    private String errMsg;
    private String fullZipUrl;
}
