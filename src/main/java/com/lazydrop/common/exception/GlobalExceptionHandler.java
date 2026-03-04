package com.lazydrop.common.exception;

import com.lazydrop.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LazyDropException.class)
    public ResponseEntity<ApiError> handleLazyDrop(LazyDropException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        details.put("fields", fieldErrors);

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Request validation failed", req, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("violations", ex.getConstraintViolations().stream()
                .map(v -> Map.of(
                        "path", String.valueOf(v.getPropertyPath()),
                        "message", v.getMessage()
                ))
                .toList());

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Constraint violation", req, details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), req, null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), req, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You don't have permission to do that", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception ex, HttpServletRequest req) {
        String requestId = requestId(req);
        log.error("Unhandled error requestId={} path={}", requestId, req.getRequestURI(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_SERVER_ERROR",
                        "Something went wrong",
                        requestId
                ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest req
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", ex.getMethod());
        details.put("supported", ex.getSupportedHttpMethods() != null
                ? ex.getSupportedHttpMethods()
                .stream()
                .map(HttpMethod::name)
                .toList()
                : List.of());

        return build(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "Request method '" + ex.getMethod() + "' is not supported for this endpoint",
                req,
                details
        );
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandler(
            NoHandlerFoundException ex,
            HttpServletRequest req
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("path", ex.getRequestURL());
        details.put("method", ex.getHttpMethod());

        return build(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "No handler found for this endpoint",
                req,
                details
        );
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest req,
            Map<String, Object> details
    ) {
        String requestId = requestId(req);
        ApiError body = ApiError.of(status.value(), code, message, requestId, details);
        return ResponseEntity.status(status).body(body);
    }

    private String requestId(HttpServletRequest req) {
        Object existing = req.getAttribute("requestId");
        if (existing != null) return String.valueOf(existing);
        String id = UUID.randomUUID().toString();
        req.setAttribute("requestId", id);
        return id;
    }
}
