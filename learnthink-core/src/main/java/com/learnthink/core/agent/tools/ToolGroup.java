package com.learnthink.core.agent.tools;

/**
 * 工具分组枚举
 * <p>定义工具的分类，用于工具组合策略和设置 UI 展示。</p>
 *
 * <ul>
 *   <li>{@link #USER_TOGGLEABLE} — 用户可在设置页开关的工具</li>
 *   <li>{@link #CONTEXT_GATED}    — 由上下文条件自动挂载的工具</li>
 *   <li>{@link #ALWAYS_ON}        — 始终挂载的工具</li>
 * </ul>
 */
public enum ToolGroup {
    /** 用户可切换的工具（设置页可见） */
    USER_TOGGLEABLE,

    /** 上下文条件自动挂载的工具 */
    CONTEXT_GATED,

    /** 始终挂载的工具 */
    ALWAYS_ON,

    /** Smart v2 专用工具（仅在 smart 模式中挂载） */
    SMART_ONLY
}
