package com.learnthink.common.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileChatResponse {
    private String reply;
    private Integer profileVersion;
    private Map<String, Object> profileFull;
    private Map<String, Object> profileDelta;
    private Map<String, Object> viz;
}
