package com.learnthink.core.agent.tools;

import java.util.List;

/**
 * 工具组合上下文
 * <p>描述当前对话轮次的上下文条件，驱动 {@link ToolComposition} 的工具挂载决策。</p>
 *
 * <p>使用 Builder 模式构建：</p>
 * <pre>{@code
 * ToolContext ctx = ToolContext.builder()
 *     .userToggledTools(List.of("web_search"))
 *     .hasCourse(true)
 *     .hasProfile(true)
 *     .build();
 * }</pre>
 */
public record ToolContext(
        List<String> userToggledTools,
        boolean hasCourse,
        boolean hasProfile,
        boolean hasLearningPath,
        boolean hasJudge0
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<String> userToggledTools = List.of();
        private boolean hasCourse = false;
        private boolean hasProfile = false;
        private boolean hasLearningPath = false;
        private boolean hasJudge0 = false;

        public Builder userToggledTools(List<String> v) { this.userToggledTools = v; return this; }
        public Builder hasCourse(boolean v) { this.hasCourse = v; return this; }
        public Builder hasProfile(boolean v) { this.hasProfile = v; return this; }
        public Builder hasLearningPath(boolean v) { this.hasLearningPath = v; return this; }
        public Builder hasJudge0(boolean v) { this.hasJudge0 = v; return this; }

        public ToolContext build() {
            return new ToolContext(userToggledTools, hasCourse, hasProfile, hasLearningPath, hasJudge0);
        }
    }
}
