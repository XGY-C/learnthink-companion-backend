package com.learnthink.common.dto.profile;

import lombok.Data;

@Data
public class ProfileStep1Output {

    private String rawSentences;

    private String rawSignals;

    private String rawPendingConfirmations;

    private String rawAccumulatingBehaviors;
}
