package com.learnthink.core.smart.domain;

/**
 * 概念掌握状态。
 * <p>v2 简化为 3 级，由 LLM 在对话中判断并更新。</p>
 */
public enum ConceptStatus {
    UNVERIFIED("未探测"),
    UNCLEAR("理解不清晰"),
    MASTERED("已掌握");

    private final String label;

    ConceptStatus(String label) { this.label = label; }
    public String label() { return label; }
}
