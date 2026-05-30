package com.learnthink.core.agent.impl;

import com.learnthink.core.agent.runtime.*;
import com.learnthink.core.agent.orchestration.ResourceGenerationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 主控 Agent — 全局路由和调度所有子 Agent
 *
 * <h3>设计原则（参见架构文档 §3）：</h3>
 * <ul>
 *   <li><b>规则驱动，非LLM驱动</b>：路由决策是确定性的（读取 Context → 匹配边条件）。
 *       LLM 路由会带来延迟、Token 消耗和不确定性。</li>
 *   <li><b>仲裁流程，而非仲裁内容</b>：OrchestratorAgent 决定下一步谁来执行，而非答案是什么。
 *       内容争议通过"送回上游"而非"我来评判"来解决。</li>
 *   <li><b>轻量</b>：纯内存操作，无 LLM 调用。实际延迟 &lt; 5ms。</li>
 * </ul>
 *
 * <h3>自治级别：L3（判断）</h3>
 * <p>OrchestratorAgent 自主判断前置条件是否满足并决定流程方向。</p>
 */
@Component
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);

    public String name() {
        return "OrchestratorAgent";
    }

    public AgentResult<ResourceGenerationState> execute(ResourceGenerationState state, AgentContext ctx) {
        log.info("=== OrchestratorAgent START === stage={}, status={}", state.stage, state.status);
        Instant start = Instant.now();

        ctx.observation().onPrompt(name(), "Route decision for stage=" + state.stage,
            Map.of("status", state.status, "percent", state.percent));

        // OrchestratorAgent 不修改状态——仅记录路由决策
        // 实际路由由 StateGraph 的条件边执行
        // 该 Agent 的存在仅为了使路由逻辑可见且可追踪

        String decision = determineNextPhase(state, ctx);
        log.info("Routing decision: {} -> {}", state.stage, decision);
        ctx.observation().onDecision(name(), decision,
            "Routing from " + state.stage + " based on state conditions");

        long elapsed = java.time.Duration.between(start, Instant.now()).toMillis();
        ctx.put("orchestrator_decision", decision);
        ctx.put("orchestrator_timestamp", Instant.now().toString());

        log.info("OrchestratorAgent: stage={} status={} decision={} (took {}ms)", 
                state.stage, state.status, decision, elapsed);

        return AgentResult.of(state, AgentResult.TokenUsage.ZERO, elapsed,
            Map.of("agent", name(), "decision", decision, "stage", state.stage));
    }

    /**
     * 确定性路由逻辑——读取 Context、匹配条件、返回下一阶段
     * <p>这是多 Agent 系统的"大脑"，但它是规则引擎，而非 LLM。</p>
     */
    private String determineNextPhase(ResourceGenerationState state, AgentContext ctx) {
        String stage = state.stage != null ? state.stage : "PENDING";
        String status = state.status != null ? state.status : "PENDING";

        // 终态——不再继续路由
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            return "END";
        }

        // 入口点
        if ("PENDING".equals(status) || "PENDING".equals(stage)) {
            return "PROFILING";
        }

        return switch (stage) {
            case "PROFILING" -> {
                if (state.profileSummary != null && state.profileSummary.dimensionCount() >= 6) {
                    yield "RETRIEVING";
                }
                yield "FAIL"; // profile not ready
            }
            case "RETRIEVING" -> {
                if (Boolean.TRUE.equals(ctx.get("forceLowConfidence")) && state.retrieval.totalSources() == 0) {
                    yield "FALLBACK";
                }
                yield "PLANNING";
            }
            case "PLANNING" -> "GENERATING";
            case "GENERATING" -> "REVIEWING";
            case "REVIEWING" -> {
                // 检查上下文中的审查结果
                @SuppressWarnings("unchecked")
                var reviewResults = (Map<String, ResourceGenerationState.ReviewResult>)
                    ctx.memory().get("reviewResults");
                if (reviewResults == null || reviewResults.isEmpty()) {
                    yield "PUBLISHING";
                }
                long rejected = reviewResults.values().stream()
                    .filter(r -> r.action() == ResourceGenerationState.ReviewAction.RETRY).count();
                if (rejected == 0) {
                    yield "PUBLISHING";
                }
                int reviewRetryCount = ctx.get("reviewRetryCount") != null ?
                    (int) ctx.get("reviewRetryCount") : 0;
                if (reviewRetryCount >= 2) {
                    yield "PUBLISHING"; // 最大重试次数已耗尽
                }
                yield "GENERATING"; // 重新生成被拒绝的内容
            }
            case "PUBLISHING" -> "END";
            case "FALLBACK" -> "GENERATING";
            default -> "END";
        };
    }

    public boolean isRetryable() { return false; }
}
