package com.learnthink.core.service;

import com.learnthink.common.dto.chat.SmartAssistantRequest;
import com.learnthink.common.dto.chat.SseEvent;
import reactor.core.publisher.Flux;

/**
 * 智能助手服务接口
 * @author 谢光益
 * @since 2026/5/26
 */
public interface SmartAssistantService {

    /**
     * 智能助手回答问题（流式返回）
     * @param request 请求（question + courseId）
     * @param userId 用户ID
     * @return SSE 事件流
     */
    Flux<SseEvent> answer(SmartAssistantRequest request, String userId);
}
