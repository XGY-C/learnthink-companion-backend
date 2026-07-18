package com.learnthink.common.dto.user;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class DailyDetailResponse {
    private String date;
    private Integer totalLearningSeconds;      // 当日总学习时长
    private List<QuizItem> quizzes;            // 当日练习
    private List<ResourceItem> resources;      // 当日学习资源
    private List<ChatItem> chats;              // 当日对话

    @Data
    public static class QuizItem {
        private String time;       // "14:30"
        private String topic;      // "链表操作"
        private BigDecimal score;  // 85
        private Integer durationSeconds;
    }

    @Data
    public static class ResourceItem {
        private String time;       // "16:00"
        private String title;      // "A*算法讲解"
        private String type;       // doc | code | video | mindmap
        private String status;     // in_progress | completed
    }

    @Data
    public static class ChatItem {
        private String time;       // "20:15"
        private String title;      // 会话标题
        private Integer messageCount; // 消息条数
    }
}
