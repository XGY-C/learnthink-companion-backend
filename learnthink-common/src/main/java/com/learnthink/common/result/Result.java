package com.learnthink.common.result;

import lombok.Data;

/**
 * 统一响应体
 */
@Data
public class Result<T> {
    private Object code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(0);
        result.setMessage("ok");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }
    
    public static <T> Result<T> success(T data, String message) {
        Result<T> result = new Result<>();
        result.setCode(0);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(Object code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}
