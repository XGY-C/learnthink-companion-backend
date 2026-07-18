package com.learnthink.core.service.impl;

import com.learnthink.core.service.EvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 通用文本评价服务实现。
 * <p>基于 {@code chatChatClientBuilder} 调用 LLM，按统一维度与固定 Markdown 结构输出评价。
 * 调用失败时返回兜底评价文本，不抛异常，保证调用方可用性。
 */
@Service
public class EvaluationServiceImpl implements EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationServiceImpl.class);

    private static final String SYSTEM_PROMPT = """
        你是学思伴行（LearnThink Companion）的学习评价专家。
        你的任务是对给定文本按统一标准进行客观、专业的结构化评价。

        【评价维度】
        1. 准确性：内容是否正确、有无事实或概念错误
        2. 完整性：是否覆盖核心要点、有无遗漏
        3. 条理性：逻辑与结构是否清晰、层次是否分明
        4. 深度：理解与思考是否到位、有无见解

        【输出规范】
        严格按以下 Markdown 结构输出，不得增删章节，不得输出结构之外的内容：
        ## 总体评价
        <一句话总评>（等级：优秀 / 良好 / 合格 / 待改进）

        ## 维度评价
        - **准确性**：<一句话评语> ［强 / 中 / 弱］
        - **完整性**：<一句话评语> ［强 / 中 / 弱］
        - **条理性**：<一句话评语> ［强 / 中 / 弱］
        - **深度**：<一句话评语> ［强 / 中 / 弱］

        ## 亮点
        - <具体值得肯定的点，1-3 条；若无则写"暂无明显亮点">

        ## 不足
        - <具体问题，1-3 条；若无则写"暂无明显不足">

        ## 改进建议
        1. <可操作的下一步建议，2-3 条>

        【要求】
        - 评语要具体、指向文本本身，避免空话套话
        - 等级与各维度判定需前后一致
        - 只输出评价，不要复述原文
        """;

    private final ChatClient chatClient;

    public EvaluationServiceImpl(
            @Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String evaluate(String text) {
        return evaluate(text, null);
    }

    @Override
    public String evaluate(String text, String hint) {
        if (text == null || text.isBlank()) {
            return "## 总体评价\n待评价内容为空，无法评价。（等级：待改进）";
        }

        StringBuilder userPrompt = new StringBuilder();
        if (hint != null && !hint.isBlank()) {
            userPrompt.append("【评价场景提示】").append(hint.trim()).append("\n\n");
        }
        userPrompt.append("【待评价文本】\n").append(text);

        try {
            String result = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt.toString())
                    .call()
                    .content();
            if (result == null || result.isBlank()) {
                return fallback("评价结果为空");
            }
            return result.trim();
        } catch (Exception e) {
            log.error("EvaluationService failed: {}", e.getMessage(), e);
            return fallback("评价服务异常: " + e.getMessage());
        }
    }

    private String fallback(String reason) {
        return "## 总体评价\n评价未能完成（" + reason + "）。（等级：待改进）\n\n"
             + "## 改进建议\n1. 稍后重试，或联系系统管理员。";
    }
}
