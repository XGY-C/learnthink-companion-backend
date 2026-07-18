package com.learnthink.core.service;

import com.learnthink.common.dto.plan.ActivitySubmitRequest;
import com.learnthink.common.dto.plan.ActivitySubmitResponse;
import com.learnthink.common.dto.plan.PlanGenerateRequest;
import com.learnthink.common.dto.plan.PlanResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;

/**
 * 学习路径服务接口 (v3.0)
 */
public interface PlanService {

    /**
     * 获取当前学习计划（含所有子计划）
     */
    PlanResponse getCurrentPlan(String userId, String courseId);

    /**
     * 获取单个 module 子计划
     */
    PlanResponse.SubPlanDto getModuleSubPlan(String userId, String planId, String moduleId);

    /**
     * 提交 activity 结果（quiz 评分 / learn 计时 / explore 完成）
     */
    ActivitySubmitResponse submitActivity(String userId, String activityId, ActivitySubmitRequest request);

    /**
     * 重新规划单个 module
     */
    PlanResponse.SubPlanDto replanModule(String userId, String planId, String moduleId);

    /**
     * 重新生成单个 activity
     */
    PlanResponse.ActivityDto regenerateActivity(String userId, String planId, String moduleId, String activityId);

    /**
     * 以最新画像刷新所有未开始 module
     */
    PlanResponse refreshFutureModules(String userId, String planId);

    /**
     * 获取 activity 下每个资源的学习状态
     */
    List<Map<String, Object>> getResourceStatus(String userId, String activityId, String moduleId);

    /**
     * 更新 activity 下某个资源的学习状态
     */
    void updateResourceStatus(String userId, String activityId, String moduleId, String resourceType, String status, Integer durationSeconds);

    /**
     * 对 quiz activity 的最近一次作答生成智能评估分析（SSE 流式）。
     * 已有缓存评估时直接回放，否则流式生成并持久化到 quiz_attempts.evaluation。
     */
    void evaluateQuizActivity(String userId, String activityId, SseEmitter emitter);

    /**
     * 切换锁定模式并重算锁定状态
     */
    PlanResponse updateLockMode(String userId, String courseId, String lockMode);
}
