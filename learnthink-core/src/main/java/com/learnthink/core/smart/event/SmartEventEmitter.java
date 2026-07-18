package com.learnthink.core.smart.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.smart.domain.SmartContext;
import com.learnthink.core.tutoring.domain.QuestionAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart 模式 SSE 事件发射器。
 *
 * <h3>事件类型</h3>
 * <ul>
 *   <li>{@code agent.thought}     - 思考过程（LLM 内部推理或工具调用前的思考）</li>
 *   <li>{@code agent.tool_call}   - 工具调用开始</li>
 *   <li>{@code agent.tool_result} - 工具结果</li>
 *   <li>{@code agent.visual}      - 可视化产物（前端内联渲染）</li>
 *   <li>{@code chunk}             - 文本输出（流式，前端追加）</li>
 *   <li>{@code smart.state}       - 状态更新</li>
 *   <li>{@code smart.converged}   - 收敛信号</li>
 *   <li>{@code smart.started}     - 首次启动</li>
 *   <li>{@code done}              - 本轮完成</li>
 *   <li>{@code error}             - 错误</li>
 * </ul>
 */
public class SmartEventEmitter {
    private static final Logger log = LoggerFactory.getLogger(SmartEventEmitter.class);

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    /** 心跳调度器 */
    private ScheduledExecutorService heartbeatScheduler;
    private final AtomicBoolean heartbeatActive = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public SmartEventEmitter(SseEmitter emitter) {
        this(emitter, new ObjectMapper());
    }

    public SmartEventEmitter(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
        startHeartbeat();
    }

    // ── 事件发送方法 ──────────────────────────────────────

    /** smart.started：首次启动 */
    public void smartStarted(String sessionId, QuestionAnalysis analysis,
                              List<com.learnthink.core.smart.domain.ConceptBreakdown> concepts) {
        Map<String, Object> data = Map.of(
            "sessionId", sessionId,
            "analysis", analysis != null ? analysis : Map.of(),
            "concepts", concepts != null ? concepts : List.of(),
            "timestamp", Instant.now().toString()
        );
        send("smart.started", data);
    }

    /** agent.thought：思考事件 */
    public void thought(String phase, String content) {
        send("agent.thought", Map.of(
            "phase", phase,
            "content", content,
            "timestamp", Instant.now().toString()
        ));
    }

    /** agent.tool_call：工具调用开始 */
    public void toolCallStart(String toolName, String args) {
        send("agent.tool_call", Map.of(
            "tool", toolName,
            "args", truncate(args, 500),
            "timestamp", Instant.now().toString()
        ));
    }

    /** agent.tool_result：工具结果 */
    public void toolResult(String toolName, String resultSummary, boolean success) {
        send("agent.tool_result", Map.of(
            "tool", toolName,
            "result", truncate(resultSummary, 1000),
            "success", success,
            "timestamp", Instant.now().toString()
        ));
    }

    /**
     * agent.visual：可视化产物。
     * <p>前端在此处插入内联渲染，不是单独的消息，而是当前消息流的一部分。</p>
     */
    public void visual(String toolName, String renderType, String code, String description) {
        send("agent.visual", Map.of(
            "renderType", renderType,
            "code", code,
            "description", description != null ? description : "",
            "tool", toolName,
            "timestamp", Instant.now().toString()
        ));
    }

    /** chunk：流式文本输出（原始文本，非 JSON） */
    public void chunk(String text) {
        if (completed.get()) return;
        try {
            emitter.send(SseEmitter.event().name("chunk").data(text));
        } catch (IOException e) {
            log.debug("SSE chunk failed: {}", e.getMessage());
        }
    }

    /** smart.state：状态更新 */
    public void stateUpdate(SmartContext ctx) {
        send("smart.state", Map.of(
            "concepts", ctx.conceptStatus() != null ? ctx.conceptStatus() : Map.of(),
            "totalTurns", ctx.totalTurns(),
            "converged", ctx.converged()
        ));
    }

    /** smart.converged：收敛 */
    public void smartConverged(String summary) {
        send("smart.converged", Map.of(
            "summary", summary != null ? summary : "",
            "timestamp", Instant.now().toString()
        ));
    }

    /** done：本轮完成 */
    public void done(SmartContext ctx) {
        send("done", Map.of(
            "converged", ctx.converged(),
            "totalTurns", ctx.totalTurns()
        ));
        complete();
    }

    /** error：错误 */
    public void error(String code, String message, boolean retryable) {
        send("error", Map.of(
            "code", code,
            "message", message != null ? message : "",
            "retryable", retryable
        ));
        complete();
    }

    /** 发送 SseEvent（由 flatMap 统一 flush 时使用） */
    public void send(SseEvent event) {
        if (completed.get()) return;
        if (event.rawText()) {
            chunk((String) event.data());
        } else {
            send(event.name(), event.data());
        }
    }

    // ── 内部方法 ──────────────────────────────────────────

    /** 发送命名事件（data 为 JSON 字符串） */
    private void send(String eventName, Object data) {
        if (completed.get()) return;
        try {
            String json = data instanceof String s ? s : objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            log.debug("SSE send failed for event '{}': {}", eventName, e.getMessage());
        } catch (Exception e) {
            log.error("SSE send error for event '{}': {}", eventName, e.getMessage());
        }
    }

    /** 完成 SSE 流 */
    public void complete() {
        if (completed.compareAndSet(false, true)) {
            stopHeartbeat();
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("SSE complete error: {}", e.getMessage());
            }
        }
    }

    // ── 心跳 ──────────────────────────────────────────────

    private void startHeartbeat() {
        if (heartbeatActive.get()) return;
        heartbeatActive.set(true);
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smart-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!completed.get()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                }
            } catch (IOException e) {
                log.debug("Smart SSE heartbeat failed (client disconnected)");
                stopHeartbeat();
            } catch (Exception e) {
                log.debug("Smart SSE heartbeat error: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        heartbeatActive.set(false);
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
