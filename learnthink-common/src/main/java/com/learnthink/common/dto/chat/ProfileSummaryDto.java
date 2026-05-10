package com.learnthink.common.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileSummaryDto {
    private String profileVersionId;
    private int version;
    private Map<String, Object> summary;
    private Map<String, Object> dimensions;
}
