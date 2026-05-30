package com.learnthink.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视频生成 AI 配置
 * <p>集中管理 ChatClient Bean 及其特定的系统提示词。</p>
 */
@Configuration
@RequiredArgsConstructor
public class VideoAIConfig {
    
    private final PromptLoader promptLoader;
    
    /**
     * 视频脚本生成器——将项目简介转换为结构化教学脚本
     * <p>使用 generation 预设进行创意输出（temperature=0.8）。</p>
     */
    @Bean("videoScriptChatClient")
    public ChatClient videoScriptChatClient(
        @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        
        return builder.clone()
            .defaultSystem(promptLoader.get("video/script-generator"))
            .build();
    }
    
    /**
     * 场景 JSON 生成器——将脚本转换为 Manim 场景规范
     * <p>使用 generation 预设进行结构化 JSON 输出。</p>
     */
    @Bean("videoSceneChatClient")
    public ChatClient videoSceneChatClient(
        @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        
        return builder.clone()
            .defaultSystem(promptLoader.get("video/scene-generator"))
            .build();
    }
}
