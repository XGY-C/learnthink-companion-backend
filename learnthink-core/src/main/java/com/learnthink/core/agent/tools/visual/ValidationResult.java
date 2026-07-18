package com.learnthink.core.agent.tools.visual;

/**
 * 可视化代码本地校验结果。
 *
 * @param ok    是否通过校验
 * @param error 校验失败时的错误描述（LLM 可据此修复）
 */
public record ValidationResult(boolean ok, String error) {
    public static ValidationResult success() {
        return new ValidationResult(true, "");
    }

    public static ValidationResult fail(String error) {
        return new ValidationResult(false, error);
    }
}
