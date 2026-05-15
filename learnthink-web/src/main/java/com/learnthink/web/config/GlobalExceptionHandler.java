package com.learnthink.web.config;

import com.learnthink.common.exception.BusinessException;
import com.learnthink.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 全局异常处理器
 *
 * <p>注意：SSE 端点（/tasks/{taskId}/events）的异常不会被转换为 JSON Result，
 * 因为 SSE 的 Content-Type 为 text/event-stream，与 Result 的 JSON 序列化不兼容。
 * SSE 发送失败（客户端断开）属正常情况，仅记录日志，不抛出 500 响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理其他异常—跳过 SSE 端点
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        // SSE 端点不需要返回 JSON 错误响应（SseEmitter 已断开）
        if (isSseRequest(request)) {
            log.warn("SSE endpoint exception (client disconnected): {}", e.getMessage());
            return null;
        }
        // SSE 端点也不需要 500 错误码
        log.error("Unexpected exception", e);
        return Result.error("服务器内部错误");
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return (path != null && path.contains("/tasks/") && path.contains("/events"))
            || (path != null && path.contains("/chat/") && path.contains("/stream"))
            || "text/event-stream".equals(request.getContentType());
    }
}
