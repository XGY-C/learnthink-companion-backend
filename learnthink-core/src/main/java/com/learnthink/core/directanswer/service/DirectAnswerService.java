package com.learnthink.core.directanswer.service;

import com.learnthink.core.directanswer.domain.request.DirectAnswerStartRequest;
import com.learnthink.core.directanswer.domain.response.DirectAnswerResponse;
import com.learnthink.core.directanswer.event.DirectAnswerEventEmitter;

import java.util.List;
import java.util.Map;

/**
 * DirectAnswer 服务接口。
 */
public interface DirectAnswerService {

    /**
     * 创建 DirectAnswer 会话（返回 sessionId，不触发生成）。
     */
    String createSession(DirectAnswerStartRequest request);

    /**
     * 启动直接解答（SSE 流式推送）。
     */
    void startAnswer(DirectAnswerStartRequest request, DirectAnswerEventEmitter emitter);

    /**
     * 获取历史解答（回放）。
     */
    DirectAnswerResponse getAnswer(String sessionId);

    /**
     * 列出用户的所有解答会话。
     */
    List<Map<String, Object>> listSessions(String userId, int page, int size);
}
