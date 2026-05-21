package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessagesResponse {
    private List<ChatMessageDto> messages;
    private boolean generationReady;
    private Map<String, Object> generationMeta;
    private boolean planGenerationReady;
    private Map<String, Object> planGenerationMeta;
    private List<ActiveTaskDto> activeTasks;
}
