package com.learnthink.web.controller;

import com.learnthink.common.dto.plan.ActivitySubmitRequest;
import com.learnthink.common.dto.plan.ActivitySubmitResponse;
import com.learnthink.common.dto.plan.PlanGenerateRequest;
import com.learnthink.common.dto.plan.PlanResponse;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.agent.orchestration.PlanGenerationOrchestrator;
import com.learnthink.core.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 学习路径 API (v3.0)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;
    private final PlanGenerationOrchestrator planOrchestrator;

    /**
     * 生成学习计划（任务化）
     * 返回 taskId，前端通过 GET /tasks/{taskId}/events 订阅 SSE 进度
     */
    @PostMapping("/plan/generate")
    public Result<Map<String, String>> generatePlan(@RequestBody PlanGenerateRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Plan generation requested: userId={}, courseId={}, profileVersion={}",
                userId, request.getCourseId(), request.getProfileVersion());
        String taskId = planOrchestrator.startGeneration(userId, request.getCourseId(), request.getProfileVersion());
        return Result.success(Map.of("task_id", taskId));
    }

    /**
     * 获取当前学习计划（含所有子计划）
     */
    @GetMapping("/plan/current")
    public Result<PlanResponse> getCurrentPlan(@RequestParam String courseId) {
        String userId = UserContextUtil.getCurrentUserId();
        PlanResponse plan = planService.getCurrentPlan(userId, courseId);
        if (plan == null) {
            return Result.error("PLAN_NOT_FOUND");
        }
        return Result.success(plan);
    }

    /**
     * 获取单个 module 子计划
     */
    @GetMapping("/plan/modules/{moduleId}/subplan")
    public Result<PlanResponse.SubPlanDto> getModuleSubPlan(
            @RequestParam String planId,
            @PathVariable String moduleId) {
        String userId = UserContextUtil.getCurrentUserId();
        PlanResponse.SubPlanDto subPlan = planService.getModuleSubPlan(userId, planId, moduleId);
        if (subPlan == null) {
            return Result.error("SUBPLAN_NOT_FOUND");
        }
        return Result.success(subPlan);
    }

    /**
     * 提交 activity 结果（quiz 评分 / learn 计时 / explore 完成）
     */
    @PostMapping("/plan/activities/{activityId}/submit")
    public Result<ActivitySubmitResponse> submitActivity(
            @PathVariable String activityId,
            @RequestBody ActivitySubmitRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        try {
            ActivitySubmitResponse response = planService.submitActivity(userId, activityId, request);
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            log.warn("Activity not found: activityId={}", activityId);
            return Result.error("ACTIVITY_NOT_FOUND");
        }
    }

    /**
     * 重新规划单个 module
     */
    @PostMapping("/plan/modules/{moduleId}/replan")
    public Result<PlanResponse.SubPlanDto> replanModule(
            @RequestParam String planId,
            @PathVariable String moduleId) {
        String userId = UserContextUtil.getCurrentUserId();
        try {
            PlanResponse.SubPlanDto result = planService.replanModule(userId, planId, moduleId);
            return Result.success(result);
        } catch (UnsupportedOperationException e) {
            return Result.error("NOT_IMPLEMENTED");
        }
    }

    /**
     * 重新生成单个 activity
     */
    @PostMapping("/plan/modules/{moduleId}/activities/{activityId}/regenerate")
    public Result<PlanResponse.ActivityDto> regenerateActivity(
            @RequestParam String planId,
            @PathVariable String moduleId,
            @PathVariable String activityId) {
        String userId = UserContextUtil.getCurrentUserId();
        try {
            PlanResponse.ActivityDto result = planService.regenerateActivity(userId, planId, moduleId, activityId);
            return Result.success(result);
        } catch (UnsupportedOperationException e) {
            return Result.error("NOT_IMPLEMENTED");
        }
    }

    /**
     * 以最新画像刷新所有未开始 module
     */
    @PostMapping("/plan/refresh")
    public Result<PlanResponse> refreshFutureModules(@RequestParam String planId) {
        String userId = UserContextUtil.getCurrentUserId();
        try {
            PlanResponse result = planService.refreshFutureModules(userId, planId);
            return Result.success(result);
        } catch (UnsupportedOperationException e) {
            return Result.error("NOT_IMPLEMENTED");
        }
    }
}
