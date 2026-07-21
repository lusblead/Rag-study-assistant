package com.rag.backend.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // -- 业务异常 ---------------------------------------------------
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        return ResponseEntity.status(e.getCode())
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    // -- 参数校验异常 -------------------------------------------------
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(400)
                .body(Result.fail(400, e.getMessage()));
    }

    // -- 文件上传过大 -------------------------------------------------
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleFileTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(400)
                .body(Result.fail(400, "上传文件大小超过限制"));
    }

    // -- 运行时异常 ---------------------------------------------------
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> handleRuntime(RuntimeException e) {
        return ResponseEntity.status(500)
                .body(Result.fail(500, e.getMessage()));
    }

    // -- 兜底：未预期的系统错误 -------------------------------------
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        return ResponseEntity.status(500)
                .body(Result.fail(500, "系统内部错误"));
    }
}
