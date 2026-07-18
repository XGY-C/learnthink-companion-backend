package com.learnthink.web.controller;

import com.learnthink.common.dto.tutoring.GuidedAnswerRequest;
import com.learnthink.common.dto.tutoring.RegenerateSectionRequest;
import com.learnthink.common.dto.tutoring.TutoringStartRequest;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.tutoring.history.TutoringHistoryService;
import com.learnthink.core.tutoring.service.TutoringService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/tutoring")
public class TutoringController {

    private final TutoringService tutoringService;
    private final TutoringHistoryService historyService;

    public TutoringController(TutoringService tutoringService,
                               TutoringHistoryService historyService) {
        this.tutoringService = tutoringService;
        this.historyService = historyService;
    }

    @PostMapping("/start")
    public SseEmitter start(@RequestBody TutoringStartRequest request) {
        SseEmitter emitter = new SseEmitter(600_000L);
        UserContextUtil.UserInfo userInfo = UserContextUtil.getCurrentUser();
        CompletableFuture.runAsync(() -> {
            UserContextUtil.setCurrentUser(userInfo);
            try {
                tutoringService.startTutoring(request, emitter);
            } finally {
                UserContextUtil.clear();
            }
        });
        return emitter;
    }

    @PostMapping("/{sessionId}/regenerate-section")
    public SseEmitter regenerateSection(@PathVariable String sessionId,
                                         @RequestBody RegenerateSectionRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        UserContextUtil.UserInfo userInfo = UserContextUtil.getCurrentUser();
        CompletableFuture.runAsync(() -> {
            UserContextUtil.setCurrentUser(userInfo);
            try {
                tutoringService.regenerateSection(sessionId, request.sectionId(),
                    request.action(), request.instruction(), emitter);
            } finally {
                UserContextUtil.clear();
            }
        });
        return emitter;
    }

    @PostMapping(value = "/guided/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter guidedAnswer(@RequestBody GuidedAnswerRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        UserContextUtil.UserInfo userInfo = UserContextUtil.getCurrentUser();
        CompletableFuture.runAsync(() -> {
            UserContextUtil.setCurrentUser(userInfo);
            try {
                tutoringService.resumeGuidedDialogue(request, emitter);
            } finally {
                UserContextUtil.clear();
            }
        });
        return emitter;
    }

    @GetMapping("/{sessionId}/history")
    public Result<?> getHistory(@PathVariable String sessionId) {
        return Result.success(tutoringService.getHistory(sessionId));
    }

    @GetMapping("/sessions")
    public Result<?> listSessions(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(required = false) String courseId) {
        return Result.success(tutoringService.listSessions(page, courseId));
    }

    // ======================== Phase 5 H2: 辅导历史与统计 API ========================

    /**
     * GET /tutoring/history?userId=&courseId=&page=1&size=20
     * 按用户 + 课程分页查询辅导历史（含 sub_mode 过滤、完成状态）。
     */
    @GetMapping("/history")
    public Result<?> history(@RequestParam(required = false) String userId,
                              @RequestParam(required = false) String courseId,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size) {
        // 如果未传 userId，使用当前登录用户
        if (userId == null || userId.isBlank()) {
            userId = UserContextUtil.getCurrentUserId();
        }
        return Result.success(historyService.getHistory(userId, courseId, page, size));
    }

    /**
     * GET /tutoring/stats?userId=&courseId=
     * 辅导统计聚合：总数、完成率、子模式分布、KP 覆盖、反馈统计。
     */
    @GetMapping("/stats")
    public Result<?> stats(@RequestParam(required = false) String userId,
                            @RequestParam(required = false) String courseId) {
        if (userId == null || userId.isBlank()) {
            userId = UserContextUtil.getCurrentUserId();
        }
        return Result.success(historyService.getStats(userId, courseId));
    }

    /**
     * GET /tutoring/sessions/{sessionId}/detail
     * 获取单次辅导会话完整详情（章节、KP 链接、关联聊天消息）。
     */
    @GetMapping("/sessions/{sessionId}/detail")
    public Result<?> sessionDetail(@PathVariable String sessionId) {
        Map<String, Object> detail = historyService.getSessionDetail(sessionId);
        if (detail.isEmpty()) {
            return Result.error(404, "会话不存在");
        }
        return Result.success(detail);
    }

    // ======================== 原有 API ========================

    @PostMapping("/{sessionId}/feedback")
    public Result<?> submitFeedback(@PathVariable String sessionId,
                                     @RequestBody Map<String, String> body) {
        tutoringService.submitFeedback(sessionId, body.get("rating"), body.get("comment"));
        return Result.success(null);
    }

    @PostMapping("/{sessionId}/diagram/retry")
    public Result<?> retryDiagram(@PathVariable String sessionId,
                                   @RequestBody Map<String, String> body) {
        tutoringService.retryDiagram(sessionId, body.get("diagramId"));
        return Result.success(null);
    }
}
