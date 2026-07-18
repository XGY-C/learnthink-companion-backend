package com.learnthink.core.smart.domain;

/**
 * Smart 模式教学阶段。
 * <p>用于按阶段动态裁剪工具集，避免工具过多导致 LLM 选择准确率下降。</p>
 */
public enum SmartPhase {
    /** 探索阶段：讲解概念、出题探测 */
    EXPLORE,
    /** 迁移阶段：所有概念已掌握，出变形题检验迁移能力 */
    TRANSFER,
    /** 已收敛：教学完成，自由提问 */
    CONVERGED
}
