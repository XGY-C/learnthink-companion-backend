package com.learnthink.common.dto.chat;

/**
 * Typed SSE event for streaming responses.
 * Named events ("agent.thought", "done") are sent as named SSE events.
 * Chunk events (null eventName) are sent as "chunk" SSE events for LLM token streaming.
 */
public class SseEvent {

    private final String eventName;
    private final String data;

    private SseEvent(String eventName, String data) {
        this.eventName = eventName;
        this.data = data;
    }

    public static SseEvent chunk(String data) {
        return new SseEvent(null, data);
    }

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
