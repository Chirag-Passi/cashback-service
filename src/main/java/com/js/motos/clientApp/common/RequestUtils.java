package com.js.motos.clientApp.common;

import jakarta.servlet.http.HttpServletRequest;

/** Helpers for pulling client metadata off the incoming request. */
public final class RequestUtils {

    private RequestUtils() {
    }

    /**
     * Best-effort client IP. Trusts {@code X-Forwarded-For} because the app runs
     * behind a platform proxy (Render/Railway/Fly); the left-most entry is the
     * original client. Only ever used for rate limiting and audit context, never
     * for authorization.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return truncate(first.trim(), 45);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return truncate(realIp.trim(), 45);
        }
        return truncate(request.getRemoteAddr(), 45);
    }

    public static String userAgent(HttpServletRequest request) {
        return truncate(request.getHeader("User-Agent"), 255);
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}