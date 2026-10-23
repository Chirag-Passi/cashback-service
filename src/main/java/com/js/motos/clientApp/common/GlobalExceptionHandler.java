package com.js.motos.clientApp.common;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        // Expected outcome — log at debug so production logs stay signal-heavy.
        log.debug("API exception [{}] on {}: {}", ex.code(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ex.code().status())
                .body(ApiError.of(ex.code(), ex.getMessage(), request.getRequestURI(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                        request.getRequestURI(), Map.of("fieldErrors", fieldErrors)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED, ex.getMessage(),
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED,
                        "Missing required file part '" + ex.getRequestPartName() + "'",
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException ex,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED, "Uploaded file is too large",
                        request.getRequestURI(), Map.of()));
    }

    /**
     * Losing an optimistic-lock race means someone else redeemed the QR (or moved
     * the redemption on) a moment earlier. Report it as a conflict, not a 500.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                         HttpServletRequest request) {
        log.debug("Optimistic lock conflict on {}", request.getRequestURI());
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ApiError.of(ErrorCode.CONFLICT,
                        "This record was modified by someone else. Please retry.",
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                    HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ApiError.of(ErrorCode.CONFLICT,
                        "The request conflicts with existing data.",
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(ApiError.of(ErrorCode.FORBIDDEN, "You do not have access to this resource",
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex,
                                               HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.status())
                .body(ApiError.of(ErrorCode.UNAUTHENTICATED, "Authentication required",
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandler(NoHandlerFoundException ex,
                                                    HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiError.of(ErrorCode.NOT_FOUND, "No such endpoint",
                        request.getRequestURI(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, "Something went wrong",
                        request.getRequestURI(), Map.of()));
    }
}