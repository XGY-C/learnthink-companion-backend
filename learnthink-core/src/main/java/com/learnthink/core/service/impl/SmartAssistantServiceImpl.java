package com.learnthink.core.service.impl;

import com.learnthink.common.dto.chat.SmartAssistantRequest;
import com.learnthink.common.dto.chat.SseEvent;
import com.learnthink.core.service.SmartAssistantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 智能助手服务实现类
 * @author 谢光益
 * @since 2026/5/26
 */
@Slf4j
@Service
public class SmartAssistantServiceImpl implements SmartAssistantService {

    private final ChatClient.Builder chatClientBuilder;

    public SmartAssistantServiceImpl(@Qualifier("chatChatClientBuilder") ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    @Override
    public Flux<SseEvent> answer(SmartAssistantRequest request, String userId) {
        if (userId == null || userId.isBlank()) {
            return Flux.error(new IllegalArgumentException("用户ID不能为空"));
        }
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return Flux.error(new IllegalArgumentException("问题不能为空"));
        }

        String question = request.getQuestion();
        log.info("开始回答，用户 {} 课程 {} 问题：{}", userId, request.getCourseId(), question);

        return Flux.concat(
            Flux.just(SseEvent.named("connected", "{\"message\":\"连接成功\"}")),
            chatClientBuilder.build().prompt()
                .user(question)
                .stream()
                .content()
                .map(SseEvent::chunk),
            Flux.just(SseEvent.named("done", "{\"message\":\"回答完成\"}"))
        ).onErrorResume(error -> {
            log.error("处理过程中发生错误", error);
            String msg = error.getMessage() != null ? error.getMessage() : "未知错误";
            return Flux.just(SseEvent.named("error", "{\"message\":\"处理失败: " + msg + "\"}"));
        });
    }
}
