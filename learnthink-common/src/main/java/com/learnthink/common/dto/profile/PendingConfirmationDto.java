package com.learnthink.common.dto.profile;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PendingConfirmationDto {

    private String dimension;

    private String signalKey;

    private String value;

    private String suggestedQuestion;

    private String confirmedPendingId;

    private String rejectedPendingId;
}
