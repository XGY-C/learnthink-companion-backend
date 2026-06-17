package com.learnthink.common.dto.profile;

import lombok.Data;

@Data
public class BehaviorAccumulatorDto {

    private String signalKey;

    private String value;

    private int occurrenceCount;

    private String status;
}
