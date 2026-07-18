package com.learnthink.common.dto.practice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeStatsDTO {
    /** 累计刷题数 */
    private int totalAnswered;
    /** 平均正确率（0~1） */
    private BigDecimal avgAccuracy;
    /** 连续练习天数 */
    private int currentStreak;
    /** 待复习错题数 */
    private int wrongQuestionCount;
}
