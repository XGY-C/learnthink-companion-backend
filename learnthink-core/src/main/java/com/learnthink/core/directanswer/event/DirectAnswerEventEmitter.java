package com.learnthink.core.directanswer.event;

import com.learnthink.core.directanswer.domain.response.AnalysisResult;
import com.learnthink.core.directanswer.domain.response.SectionBlueprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DirectAnswer SSE 事件发射器。
 * 9 种 SSE 事件类型：mode / section_analysis / section_plan / thought / section_start / section_chunk / section_done / done / error
 * Phase 5 H3: 新增 SSE 心跳（每 30s 发送空注释），防止代理/Nginx 断开连接。
 */
public class DirectAnswerEventEmitter {
    private static final Logger log = LoggerFactory.getLogger(DirectAnswerEventEmitter.class);
    private final SseEmitter emitter;

    /** 心跳调度器 */
    private ScheduledExecutorService heartbeatScheduler;
    private final AtomicBoolean heartbeatActive = new AtomicBoolean(false);

    public DirectAnswerEventEmitter(SseEmitter emitter) {
        this.emitter = emitter;
        startHeartbeat();
    }

    /**
     * Phase 5 H3: SSE 心跳 — 每 30s 发送空注释 ": heartbeat"。
     */
    private void startHeartbeat() {
        if (heartbeatActive.get()) return;
        heartbeatActive.set(true);
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "direct-answer-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                log.debug("DirectAnswer SSE heartbeat failed (client disconnected)");
                stopHeartbeat();
            } catch (Exception e) {
                log.debug("DirectAnswer SSE heartbeat error: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        heartbeatActive.set(false);
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdown();
        }
    }

    /** 入口模式标识 */
    public void mode(String mode, String source) {
        send("direct.mode", Map.of("mode", mode, "source", source));
    }

    /** Phase1 分析结果 */
    public void sectionAnalysis(AnalysisResult analysis) {
        send("direct.section_analysis", analysis);
    }

    /** Phase2 段落蓝图 */
    public void sectionPlan(List<SectionBlueprint> blueprints) {
        send("direct.section_plan", Map.of("sections", blueprints));
    }

    /** Phase3 思考通道（逐字） */
    public void thought(String chunk) {
        send("direct.thought", Map.of("chunk", chunk));
    }

    /** 段落开始 */
    public void sectionStart(String sectionId, String title) {
        send("direct.section_start", Map.of("sectionId", sectionId, "title", title));
    }

    /** 段落内容片段 */
    public void sectionChunk(String sectionId, String chunk) {
        send("direct.section_chunk", Map.of("sectionId", sectionId, "chunk", chunk));
    }

    /** 段落完成 */
    public void sectionDone(String sectionId) {
        send("direct.section_done", Map.of("sectionId", sectionId));
    }

    /** 全部完成 */
    public void done(String sessionId, String answerId, int sectionCount, String mode) {
        stopHeartbeat();
        send("direct.done", Map.of(
            "sessionId", sessionId,
            "answerId", answerId,
            "sectionCount", sectionCount,
            "mode", mode));
    }

    /** 错误事件 */
    public void error(String code, String message, boolean retryable) {
        stopHeartbeat();
        send("direct.error", Map.of(
            "code", code, "message", message, "retryable", retryable));
    }

    public void complete() {
        stopHeartbeat();
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    public SseEmitter getEmitter() { return emitter; }

    private void send(String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.debug("DirectAnswer SSE send failed (client disconnected): {}", e.getMessage());
        }
    }
}
