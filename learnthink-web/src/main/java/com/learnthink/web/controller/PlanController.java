package com.learnthink.web.controller;

import com.learnthink.common.dto.plan.ActivitySubmitRequest;
import com.learnthink.common.dto.plan.ActivitySubmitResponse;
import com.learnthink.common.dto.plan.PlanConfirmRequest;
import com.learnthink.common.dto.plan.PlanGenerateRequest;
import com.learnthink.common.dto.plan.PlanPreviewRequest;
import com.learnthink.common.dto.plan.PlanResponse;
import com.learnthink.common.dto.plan.PlanUpdateRequest;
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
     * 若已有进行中任务且未传 force=true，返回 already_in_progress 标记，由前端弹窗让用户选择
     */
    @PostMapping("/plan/generate")
    public Result<Map<String, Object>> generatePlan(@RequestBody PlanGenerateRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Plan generation requested: userId={}, courseId={}, profileVersion={}, force={}",
                userId, request.getCourseId(), request.getProfileVersion(), request.isForce());

        // 非强制模式下检查是否已有进行中任务
        if (!request.isForce()) {
            String existingTaskId = planOrchestrator.findActivePlanTaskId(userId, request.getCourseId());
            if (existingTaskId != null) {
                log.info("Plan generation already in progress, returning existing taskId={}", existingTaskId);
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("task_id", existingTaskId);
                data.put("already_in_progress", true);
                return Result.success(data);
            }
        }

        String taskId = planOrchestrator.startGeneration(userId, request.getCourseId(),
                request.getProfileVersion(), request.isForce(), request.getRequirementText());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("task_id", taskId);
        return Result.success(data);
    }

    /**
     * 预览大计划（同步，不落库）
     * 返回 modules + edges + summary，供前端渲染可编辑计划
     */
    @PostMapping("/plan/preview")
    public Result<Map<String, Object>> previewPlan(@RequestBody PlanPreviewRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Plan preview requested: userId={}, courseId={}", userId, request.getCourseId());

        try {
            Map<String, Object> plan = planOrchestrator.previewPlan(userId, request.getCourseId(),
                    request.getProfileVersion(), request.getRequirementText(), request.getChatId());
            return Result.success(plan);
        } catch (Exception e) {
            log.error("Plan preview failed", e);
            return Result.error("PLAN_PREVIEW_FAILED", "大计划生成失败：" + e.getMessage());
        }
    }

    /**
     * 更新 pending_decision 计划草稿 — 用户编辑模块后实时保存到后端
     */
    @PostMapping("/plan/update")
    public Result<Map<String, Object>> updatePlan(@RequestBody PlanUpdateRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Plan draft update: userId={}, planId={}, courseId={}",
                userId, request.getPlanId(), request.getCourseId());

        try {
            Map<String, Object> plan = planOrchestrator.updatePlanDraft(
                    userId, request.getPlanId(), request.getCourseId(),
                    request.getProfileVersion(), request.getPlanJson(),
                    request.getChatId(), request.getRequirementText());
            return Result.success(plan);
        } catch (IllegalStateException e) {
            return Result.error("PLAN_NOT_EDITABLE", e.getMessage());
        } catch (Exception e) {
            log.error("Plan draft update failed", e);
            return Result.error("PLAN_UPDATE_FAILED", "计划更新失败：" + e.getMessage());
        }
    }

    /**
     * 确认大计划并异步生成子计划 — 跳过 big_plan LLM，使用用户编辑后的 plan
     */
    @PostMapping("/plan/confirm")
    public Result<Map<String, Object>> confirmPlan(@RequestBody PlanConfirmRequest request) {
        String userId = UserContextUtil.getCurrentUserId();
        log.info("Plan confirm requested: userId={}, courseId={}", userId, request.getCourseId());

        String taskId = planOrchestrator.confirmPlanAndGenerate(userId, request.getCourseId(),
                request.getProfileVersion(), request.getPlanJson(), request.getRequirementText(),
                request.getChatId());

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("task_id", taskId);
        return Result.success(data);
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
