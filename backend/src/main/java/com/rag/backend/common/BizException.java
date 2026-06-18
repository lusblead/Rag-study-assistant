package com.rag.backend.common;

/**
 * 业务异常 —— 用于在 Service 层抛出可预见的业务错误，
 * 由 GlobalExceptionHandler 统一转换为前端可读的错误信息。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public int getCode() {
        return code;
    }
}
