package com.learnthink.core.smart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.config.TutoringConfig;
import com.learnthink.core.smart.domain.ConceptBreakdown;
import com.learnthink.core.smart.domain.ConceptStatus;
import com.learnthink.core.smart.domain.SmartContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart 模式的安全边界--全部由代码强制，不依赖 LLM。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>检查是否应该强制收敛（达到最大轮数或所有概念已掌握）</li>
 *   <li>检查单轮工具调用是否超限</li>
 *   <li>兜底推断概念状态（LLM 遗忘调用 update_concept_status 时）</li>
 * </ul>
 */
@Component
public class SmartSafetyGuard {
    private static final Logger log = LoggerFactory.getLogger(SmartSafetyGuard.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TutoringConfig config;
    private final ChatClient chatClient;

    public SmartSafetyGuard(TutoringConfig config,
                            @org.springframework.beans.factory.annotation.Qualifier("chatChatClientBuilder")
                            ChatClient.Builder chatClientBuilder) {
        this.config = config;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 检查是否应该强制收敛。
     */
    public boolean shouldForceConverge(SmartContext ctx) {
        // 1. 达到最大轮数
        if (ctx.totalTurns() >= config.getSmart().getMaxInteractions()) {
            return true;
        }
        // 2. 所有概念已掌握 + 通过迁移检验
        return ctx.goal() != null && ctx.goal().isHardConverged(ctx);
    }

    /**
     * 检查单轮工具调用是否超限。
     */
    public boolean tooManyToolCalls(List<String> toolsUsedThisTurn) {
        return toolsUsedThisTurn.size() > config.getSmart().getMaxToolCallsPerTurn();
    }

    /**
     * 兜底推断概念状态。
     * <p>当 LLM 遗忘调用 {@code update_concept_status} 时，用轻量 LLM 调用从对话推断。
     * 只升级（UNVERIFIED -> UNCLEAR/MASTERED），不降级。</p>
     */
    public SmartContext fallbackInferConceptStatus(SmartContext ctx,
                                                    String userMessage, String aiReply) {
        try {
            // 1. 构建推断提示词
            StringBuilder conceptList = new StringBuilder();
            for (ConceptBreakdown c : ctx.concepts()) {
                ConceptStatus s = ctx.conceptStatus().getOrDefault(c.id(), ConceptStatus.UNVERIFIED);
                conceptList.append(c.id()).append(": ").append(c.label())
                    .append(" (当前: ").append(s.label()).append(")\n");
            }

            String prompt = """
                根据以下师生对话，推断学生对各概念的掌握状态。
                只返回 JSON：{"conceptId": "MASTERED" | "UNCLEAR" | "UNVERIFIED", ...}
                不要返回其他内容。

                概念列表：
                %s

                学生说：%s
                老师回复：%s
                """.formatted(conceptList, userMessage, aiReply);

            // 2. 轻量 LLM 调用（非流式）
            String result = chatClient.prompt().user(prompt).call().content();

            // 3. 解析并更新 conceptStatus
            Map<String, ConceptStatus> inferred = parseStatusMap(result);
            if (inferred.isEmpty()) {
                return ctx;
            }

            Map<String, ConceptStatus> merged = new HashMap<>(ctx.conceptStatus());
            inferred.forEach((id, status) -> {
                // 只升级（UNVERIFIED -> UNCLEAR/MASTERED），不降级
                ConceptStatus current = merged.getOrDefault(id, ConceptStatus.UNVERIFIED);
                if (current.ordinal() < status.ordinal()) {
                    merged.put(id, status);
                    log.info("Fallback inferred: {} -> {} (was {})", id, status, current);
                }
            });

            return ctx.withConceptStatus(merged);

        } catch (Exception e) {
            log.warn("Fallback infer concept status failed: {}", e.getMessage());
            return ctx;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, ConceptStatus> parseStatusMap(String json) {
        Map<String, ConceptStatus> result = new HashMap<>();
        if (json == null || json.isBlank()) return result;

        try {
            // 尝试直接解析 JSON
            Map<String, Object> parsed = MAPPER.readValue(json.trim(), new TypeReference<>() {});
            parsed.forEach((id, val) -> {
                try {
                    result.put(id, ConceptStatus.valueOf(String.valueOf(val).trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // 跳过无效状态值
                }
            });
        } catch (Exception e) {
            // 尝试提取 JSON 对象
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    Map<String, Object> parsed = MAPPER.readValue(
                        json.substring(start, end + 1), new TypeReference<>() {});
                    parsed.forEach((id, val) -> {
                        try {
                            result.put(id, ConceptStatus.valueOf(String.valueOf(val).trim().toUpperCase()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                } catch (Exception e2) {
                    log.warn("Failed to parse status map: {}", json);
                }
            }
        }
        return result;
    }
}
