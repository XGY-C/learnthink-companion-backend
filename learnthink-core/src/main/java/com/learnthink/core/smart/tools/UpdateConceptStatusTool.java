package com.learnthink.core.smart.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnthink.core.agent.runtime.AgentTool;
import com.learnthink.core.smart.domain.ConceptStatus;
import com.learnthink.core.smart.domain.SmartContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 更新概念掌握状态。
 * <p>LLM 在评估学生回答后调用此工具。
 * 这是 per-session 工具，不进 ToolRegistry（需要 ctxRef，不能共享）。</p>
 *
 * <h3>设计要点</h3>
 * <p>SmartContext 是 record（不可变），通过 {@code AtomicReference<SmartContext>}
 * 统一持有，工具和 doFinally 都读写 ctxRef。</p>
 */
public class UpdateConceptStatusTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(UpdateConceptStatusTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicReference<SmartContext> ctxRef;

    public UpdateConceptStatusTool(AtomicReference<SmartContext> ctxRef) {
        this.ctxRef = ctxRef;
    }

    @Override
    public String name() {
        return "update_concept_status";
    }

    @Override
    public String description() {
        return "更新学生对某个概念的掌握状态。"
             + "在评估学生回答后调用。"
             + "UNVERIFIED -> UNCLEAR（答错/不清晰）或 MASTERED（答对）。"
             + "UNCLEAR -> MASTERED（重新讲解后答对）。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "conceptId": {"type": "string", "description": "概念ID"},
                "status": {"type": "string", "enum": ["UNVERIFIED", "UNCLEAR", "MASTERED"], "description": "掌握状态"},
                "evidence": {"type": "string", "description": "判断依据（一句话）"}
              },
              "required": ["conceptId", "status"],
              "additionalProperties": false
            }""";
    }

    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);
            String conceptId = String.valueOf(args.getOrDefault("conceptId", "")).trim();
            String statusStr = String.valueOf(args.getOrDefault("status", "")).trim().toUpperCase();
            String evidence = String.valueOf(args.getOrDefault("evidence", ""));

            if (conceptId.isEmpty()) {
                return errorJson("conceptId 不能为空");
            }

            ConceptStatus status;
            try {
                status = ConceptStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                return errorJson("无效的状态: " + statusStr + "，可选: UNVERIFIED, UNCLEAR, MASTERED");
            }

            SmartContext old = ctxRef.get();
            if (old == null) {
                return errorJson("上下文不存在");
            }

            // 更新 conceptStatus
            Map<String, ConceptStatus> newStatus = new HashMap<>(old.conceptStatus());
            newStatus.put(conceptId, status);
            ctxRef.set(old.withConceptStatus(newStatus));

            log.info("UpdateConceptStatusTool: {} -> {} (evidence: {})", conceptId, status, evidence);

            return MAPPER.writeValueAsString(Map.of(
                "updated", true,
                "conceptId", conceptId,
                "status", status.name(),
                "evidence", evidence
            ));

        } catch (Exception e) {
            log.error("UpdateConceptStatusTool failed", e);
            return errorJson("更新失败: " + e.getMessage());
        }
    }

    private String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("updated", false, "error", message));
        } catch (Exception e) {
            return "{\"updated\":false,\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }
}
