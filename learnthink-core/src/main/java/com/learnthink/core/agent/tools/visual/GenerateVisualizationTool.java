package com.learnthink.core.agent.tools.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 2D 动态可视化代码生成工具。
 * <p>
 * 基于《2D 动态可视化代码生成提示词规范 v1.0》，生成单文件 HTML + Canvas 2D 动态可视化代码。
 * 采用状态机架构（预生成状态 + 逐帧播放），严格遵守 DPR 适配、响应式布局、叙事讲解等规范。
 * <p>
 * 与 {@link GenerateHtmlTool} 的区别：
 * <ul>
 *   <li>专注于算法演示、数据结构操作、数学/物理过程等动态可视化场景</li>
 *   <li>强制状态机模式（预生成状态数组 + 逐帧播放），禁止边算边画</li>
 *   <li>要求 Canvas 2D 渲染、DPR 适配、叙事讲解、图例、进度条、计数器等教学要素</li>
 *   <li>参数遵循规范定义：title / description / scenario</li>
 * </ul>
 * 返回 {@code {type:"html", code, description}} 格式，前端用沙箱 iframe 渲染。
 */
public class GenerateVisualizationTool extends AbstractVisualTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GenerateVisualizationTool(ChatClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "generate_visualization";
    }

    @Override
    public String description() {
        return "生成2D动态可视化HTML代码。输出单个HTML文件，包含完整的可视化代码，可直接在浏览器中运行。"
             + "用于算法演示（排序/搜索/图算法）、数据结构操作、数学概念、物理过程等教学场景。"
             + "代码遵守状态机架构（预生成状态+逐帧播放）、DPR适配、响应式布局、叙事讲解等规范。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "title": {"type": "string", "description": "可视化页面标题"},
                "description": {"type": "string", "description": "用户的需求描述，包括要可视化什么、教学重点等"},
                "scenario": {"type": "string", "description": "场景类型提示：sorting/searching/graph/tree/dp/math/physics/process/custom"}
              },
              "required": ["description"]
            }""";
    }

    @Override
    protected String getRenderType() {
        return "html";
    }

    @Override
    protected String getRulesPrompt() {
        return VisualPrompts.CODEGEN_RULES_VISUALIZATION;
    }

    @Override
    protected String getLanguageHint() {
        return "html";
    }

    /**
     * 重写 execute，将规范定义的参数（title/description/scenario）
     * 映射到基类期望的格式（prompt/style/history_context），然后委托给基类流水线。
     */
    @Override
    public String execute(String jsonArgs) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = MAPPER.readValue(jsonArgs, Map.class);

            String description = (String) args.getOrDefault("description", "");
            String title = (String) args.getOrDefault("title", "");
            String scenario = (String) args.getOrDefault("scenario", "");
            String historyContext = (String) args.getOrDefault("history_context", "");

            // 将 title + description 组合为 prompt
            String prompt = description != null ? description : "";
            if (title != null && !title.isBlank()) {
                prompt = "【标题】" + title + "\n" + prompt;
            }

            // scenario 映射为 style（场景类型提示）
            String style = scenario != null ? scenario : "";

            // 重建参数 JSON，委托给基类三阶段流水线
            Map<String, Object> remapped = new HashMap<>();
            remapped.put("prompt", prompt);
            remapped.put("style", style);
            remapped.put("history_context", historyContext != null ? historyContext : "");

            return super.execute(MAPPER.writeValueAsString(remapped));
        } catch (Exception e) {
            // JSON 解析失败时，直接委托给基类处理
            return super.execute(jsonArgs);
        }
    }
}
