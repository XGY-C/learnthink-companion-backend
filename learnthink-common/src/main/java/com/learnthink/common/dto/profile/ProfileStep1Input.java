package com.learnthink.common.dto.profile;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ProfileStep1Input {

    private String userId;

    private String courseId;

    private String chatId;

    private List<Map<String, String>> messages;

    private List<BehaviorAccumulatorDto> behaviorRecords;

    private List<PendingConfirmationDto> pendingConfirmations;
}
