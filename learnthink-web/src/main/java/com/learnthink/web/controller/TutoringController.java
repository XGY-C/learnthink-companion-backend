package com.learnthink.web.controller;

import com.learnthink.common.dto.tutoring.RegenerateSectionRequest;
import com.learnthink.common.dto.tutoring.TutoringStartRequest;
import com.learnthink.common.result.Result;
import com.learnthink.common.util.UserContextUtil;
import com.learnthink.core.tutoring.service.TutoringService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/tutoring")
public class TutoringController {

    private final TutoringService tutoringService;

    public TutoringController(TutoringService tutoringService) {
        this.tutoringService = tutoringService;
    }

    @PostMapping("/start")
    public SseEmitter start(@RequestBody TutoringStartRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
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

    @GetMapping("/{sessionId}/history")
    public Result<?> getHistory(@PathVariable String sessionId) {
        return Result.success(tutoringService.getHistory(sessionId));
    }

    @GetMapping("/sessions")
    public Result<?> listSessions(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(required = false) String courseId) {
        return Result.success(tutoringService.listSessions(page, courseId));
    }

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
