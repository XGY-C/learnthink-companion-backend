package com.learnthink.core.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Video generation AI configuration.
 * Centralizes ChatClient beans with their specific system prompts.
 */
@Configuration
@RequiredArgsConstructor
public class VideoAIConfig {
    
    private final PromptLoader promptLoader;
    
    /**
     * Video script generator — converts project brief to structured teaching script.
     * Uses generation preset for creative output (temperature=0.8).
     */
    @Bean("videoScriptChatClient")
    public ChatClient videoScriptChatClient(
        @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        
        return builder.clone()
            .defaultSystem(promptLoader.get("video/script-generator"))
            .build();
    }
    
    /**
     * Scene JSON generator — converts script to Manim scene specifications.
     * Uses generation preset for structured JSON output.
     */
    @Bean("videoSceneChatClient")
    public ChatClient videoSceneChatClient(
        @Qualifier("generationChatClientBuilder") ChatClient.Builder builder) {
        
        return builder.clone()
            .defaultSystem(promptLoader.get("video/scene-generator"))
            .build();
    }
}
