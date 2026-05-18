package com.learnthink.core.service;

import com.learnthink.common.dto.plan.ActivitySubmitRequest;
import com.learnthink.common.dto.plan.ActivitySubmitResponse;
import com.learnthink.common.dto.plan.PlanGenerateRequest;
import com.learnthink.common.dto.plan.PlanResponse;

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
}
