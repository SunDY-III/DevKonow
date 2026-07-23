package com.devknow.common;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理 —— ApiResponse 格式（兼容 RFC 9457 Problem Details 风格）。
 *
 * <p>对常见异常类型返回结构化的错误信息，包含：
 * <ul>
 *   <li>{@code type} — 错误类型 URI（约定）</li>
 *   <li>{@code title} — 短标题</li>
 *   <li>{@code status} — HTTP 状态码</li>
 *   <li>{@code detail} — 详细描述</li>
 *   <li>{@code instance} — 请求路径</li>
 *   <li>{@code timestamp} — 发生时间</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ProblemDetail> biz(BizException e, HttpServletRequest request) {
        return problem(e.getCode(), e.getMessage(), request);
    }

    /** 请求参数校验失败（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(400, detail, request);
    }

    /** 乐观锁冲突 */
    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> optimisticLock(HttpServletRequest request) {
        return problem(409, "工单状态已被他人修改，请刷新后重试", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> other(Exception e, HttpServletRequest request) {
        log.error("unexpected error at {}", request.getRequestURI(), e);
        return problem(500, "服务繁忙，请稍后再试", request);
    }

    private ResponseEntity<ProblemDetail> problem(int httpStatus, String detail, HttpServletRequest request) {
        ProblemDetail body = new ProblemDetail(httpStatus, detail, request.getRequestURI());
        return ResponseEntity.status(httpStatus).body(body);
    }

    // ==================== RFC 9457 Problem Detail ====================

    public record ProblemDetail(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            Instant timestamp,
            Map<String, Object> additional
    ) {
        public ProblemDetail(int httpStatus, String detail, String path) {
            this(
                    "https://developer.devknow.app/errors#" + httpStatus,
                    HttpStatus.valueOf(httpStatus).getReasonPhrase(),
                    httpStatus,
                    detail,
                    path,
                    Instant.now(),
                    Map.of()
            );
        }
    }
}
