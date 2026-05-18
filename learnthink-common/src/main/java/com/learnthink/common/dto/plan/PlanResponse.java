package com.learnthink.common.dto.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * 学习计划完整响应 (v3.0)
 * 包含大计划 + 所有子计划
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanResponse {
    private String planId;
    private Integer version;
    private Integer profileVersion;
    private String courseId;
    private String status;
    private String createdAt;

    private List<ModuleDto> modules;
    private List<EdgeDto> edges;
    private SummaryDto summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModuleDto {
        private String moduleId;
        private String title;
        private List<Map<String, String>> knowledgePoints;
        private String scope;
        private List<String> prerequisites;
        private Double estimatedHours;
        private String depth;
        private String status;
        private Double mastery;
        private String subPlanId;
        private SubPlanDto subPlan;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubPlanDto {
        private String subPlanId;
        private String moduleId;
        private Integer version;
        private List<ActivityDto> activities;
        private List<Map<String, Object>> adjustments;
        private StatsDto stats;
        private MatchSummaryDto matchSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityDto {
        private String activityId;
        private String type;
        private String title;
        private String description;
        private List<String> requires;
        private ResourceDto resource;
        private Integer estimatedMinutes;
        private Integer order;
        private CompletionCriteriaDto completionCriteria;
        private String status;
        private Integer retryCount;
        private ResultDto result;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceDto {
        private String source;
        private String resourcePackId;
        private String resourceType;
        private String generationStatus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompletionCriteriaDto {
        private String type;
        private Double threshold;
        private Boolean met;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResultDto {
        private Double score;
        private Double timeSpent;
        private String completedAt;
        private List<String> weakTags;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatsDto {
        private Double completionPct;
        private Double avgQuizScore;
        private Double totalTimeSpent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchSummaryDto {
        private Integer matchedCount;
        private Integer toGenerateCount;
        private List<Map<String, String>> toGenerate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EdgeDto {
        private String from;
        private String to;
        private String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryDto {
        private Integer totalModules;
        private Integer coreModules;
        private Integer supplementaryModules;
        private Double totalHours;
        private String completionEstimate;
    }
}
