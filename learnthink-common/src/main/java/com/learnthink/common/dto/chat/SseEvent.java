package com.learnthink.common.dto.chat;

/**
 * 类型化 SSE 事件，用于流式响应
 * <p>命名事件（"agent.thought"、"done"）以命名 SSE 事件形式发送。
 * Chunk 事件（eventName 为 null）以 "chunk" SSE 事件发送，用于 LLM Token 流式传输。</p>
 */
public class SseEvent {

    private final String eventName;
    private final String data;

    private SseEvent(String eventName, String data) {
        this.eventName = eventName;
        this.data = data;
    }

    /**
     * 创建 Token 块事件（无事件名，用于流式字符输出）
     * @param data 文本块内容
     */
    public static SseEvent chunk(String data) {
        return new SseEvent(null, data);
    }

    /**
     * 创建命名事件
     * @param name 事件名称（如 "agent.thought"、"done"）
     * @param data 事件数据 JSON 字符串
     */
    public static SseEvent named(String name, String data) {
        return new SseEvent(name, data);
    }

    public String getEventName() {
        return eventName;
    }

    public String getData() {
        return data;
    }

    public boolean isNamed() {
        return eventName != null;
    }
}
