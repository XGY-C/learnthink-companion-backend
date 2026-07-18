package com.learnthink.core.directanswer.event;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 自动 flush 的 SSE Emitter。
 * 避免 Spring SseEmitter 默认缓冲导致 SSE 事件延迟送达客户端。
 */
public class FlushableSseEmitter extends SseEmitter {
    private static final Logger log = LoggerFactory.getLogger(FlushableSseEmitter.class);

    private final HttpServletResponse response;

    public FlushableSseEmitter(Long timeout, HttpServletResponse response) {
        super(timeout);
        this.response = response;
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        super.send(builder);
        flushResponse();
    }

    private void flushResponse() {
        if (response == null) return;
        try {
            response.flushBuffer();
        } catch (IOException e) {
            log.debug("FlushableSseEmitter flush failed: {}", e.getMessage());
        } catch (IllegalStateException e) {
            log.debug("FlushableSseEmitter flush state error: {}", e.getMessage());
        }
    }
}
