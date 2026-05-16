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
     * 处理客户端断开连接的 IO 异常（SSE/流式响应场景）
     * 这类异常在用户刷新页面或关闭浏览器时非常常见，属于正常行为
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIOException(IOException e, HttpServletRequest request) {
        if (isSseRequest(request) || isClientDisconnectException(e)) {
            log.info("Client disconnected: {}", e.getMessage());
            return null;
        }
        log.warn("IO exception in non-SSE context: {}", e.getMessage());
        return Result.error("文件读写错误");
    }

    /**
     * 处理其他异常—跳过 SSE 端点
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        // SSE 端点不需要返回 JSON 错误响应（SseEmitter 已断开）
        if (isSseRequest(request)) {
            // 客户端断开属于正常情况，仅记录 INFO 级别日志，不打印堆栈
            if (isClientDisconnectException(e)) {
                log.info("SSE client disconnected: {}", e.getMessage());
            } else {
                log.warn("SSE endpoint exception: {}", e.getMessage());
            }
            return null;
        }
        // SSE 端点也不需要 500 错误码
        log.error("Unexpected exception", e);
        return Result.error("服务器内部错误");
    }

    /**
     * 判断是否为客户端主动断开连接的异常
     */
    private boolean isClientDisconnectException(Exception e) {
        String message = e.getMessage();
        if (message != null) {
            return message.contains("你的主机中的软件中止了一个已建立的连接")
                || message.contains("Connection reset by peer")
                || message.contains("Broken pipe")
                || message.contains("Connection reset")
                || message.contains("Connection closed")
                || message.contains("EofException");
        }
        
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
            String causeMsg = cause.getMessage();
            return causeMsg != null && (
                causeMsg.contains("你的主机中的软件中止了一个已建立的连接")
                || causeMsg.contains("Connection reset by peer")
                || causeMsg.contains("Broken pipe")
            );
        }
        
        return e.getClass().getName().contains("RecycleRequiredException");
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return (path != null && path.contains("/tasks/") && path.contains("/events"))
            || (path != null && path.contains("/chat/") && path.contains("/stream"))
            || "text/event-stream".equals(request.getContentType());
    }
}
