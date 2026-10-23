package com.js.motos.clientApp.common;

import java.time.Instant;
import java.util.Map;

/**
 * The single error envelope returned by every failing endpoint.
 *
 * <pre>
 * { "code": "OTP_EXPIRED", "message": "...", "path": "/api/v1/auth/otp/verify",
 *   "timestamp": "...", "details": { "fieldErrors": { ... } } }
 * </pre>
 */
public record ApiError(
        String code,
        String message,
        String path,
        Instant timestamp,
        Map<String, Object> details) {

    public static ApiError of(ErrorCode code, String message, String path, Map<String, Object> details) {
        return new ApiError(code.name(), message, path, Instant.now(),
                details == null || details.isEmpty() ? null : details);
    }
}