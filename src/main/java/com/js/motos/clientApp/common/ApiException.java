package com.js.motos.clientApp.common;

import java.util.Map;

/**
 * Application-level failure carrying a stable {@link ErrorCode}. Anything thrown
 * as an {@code ApiException} is considered an expected outcome and is rendered
 * without a stack trace.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    // --- shorthand factories used across the services ---------------------

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException of(ErrorCode code, String message) {
        return new ApiException(code, message);
    }
}