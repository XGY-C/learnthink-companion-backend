package com.learnthink.core.agent.tools.visual;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 交互式 HTML 页面生成工具。
 * <p>
 * LLM 生成完整自包含 HTML -> 本地校验（文档特征检查）-> 降级模板。
 * 返回 {@code {type:"html", code, description}} 格式，前端用沙箱 iframe 渲染。
 */
public class GenerateHtmlTool extends AbstractVisualTool {

    public GenerateHtmlTool(ChatClient.Builder builder) {
        super(builder);
    }

    @Override
    public String name() {
        return "generate_html";
    }

    @Override
    public String description() {
        return "生成交互式 HTML 页面（可拖动演示/分步走读/可点击练习/UI 原型）。"
             + "传入页面描述和交互需求，返回完整自包含的 HTML 代码。"
             + "用于需要用户操作+状态变化+图文混排的复杂场景。"
             + "可从 CDN 加载库：cdn.jsdelivr.net、cdnjs.cloudflare.com、unpkg.com。";
    }

    @Override
    public String parameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {"type": "string", "description": "页面描述：要展示什么交互内容"},
                "style": {"type": "string", "description": "交互形式偏好（interactive/animation/walkthrough/quiz，可选）"}
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    protected String getRenderType() {
        return "html";
    }

    @Override
    protected String getRulesPrompt() {
        return VisualPrompts.CODEGEN_RULES_HTML;
    }

    @Override
    protected String getLanguageHint() {
        return "html";
    }
}
