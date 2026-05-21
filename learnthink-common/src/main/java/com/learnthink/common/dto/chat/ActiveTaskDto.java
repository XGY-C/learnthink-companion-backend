package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTaskDto {
    private String taskId;
    private String topic;
    private String status;
    private String stage;
    private int percent;
    private List<String> resourceTypes;
    private String message;
    private String errorMessage;
    private int readyCount;
    private int totalCount;
}
