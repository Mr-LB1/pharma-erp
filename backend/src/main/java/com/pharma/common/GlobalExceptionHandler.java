package com.pharma.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理：统一转为 { code, message, data } 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.getCode())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /** 数据库完整性冲突（唯一键/外键/非空）→ 400 友好文案，避免前端看到 500（P2-33） */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(400)
                .body(ApiResponse.error(400, "数据冲突：记录可能已存在或仍被其他单据引用，请刷新后重试"));
    }

    /**
     * 字段级校验失败：message 保留首个错误摘要，data.errors 返回全部 {field, message}（P1-31 前端可定位输入框）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("field", f.getField());
                    item.put("message", f.getDefaultMessage());
                    return item;
                })
                .toList();
        String message = errors.isEmpty()
                ? "参数校验失败"
                : errors.get(0).get("field") + " " + errors.get(0).get("message");
        return ResponseEntity.status(400)
                .body(new ApiResponse<>(400, message, Map.of("errors", errors)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400).body(ApiResponse.error(400, "请求体格式错误"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(ApiResponse.error(403, "无权限访问"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, "资源不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(ApiResponse.error(405, "请求方法不支持"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(500).body(ApiResponse.error(500, "服务器内部错误"));
    }
}
