package com.js.motos.clientApp.common;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error identifiers. The Android client should branch
 * on these rather than on human-readable messages.
 */
public enum ErrorCode {

    // --- generic -------------------------------------------------------
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    // --- authentication ------------------------------------------------
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    OTP_INVALID(HttpStatus.BAD_REQUEST),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST),
    OTP_TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS),
    OTP_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS),
    GOOGLE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    SESSION_INVALID(HttpStatus.UNAUTHORIZED),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED),

    // --- PIN -----------------------------------------------------------
    PIN_NOT_SET(HttpStatus.CONFLICT),
    PIN_ALREADY_SET(HttpStatus.CONFLICT),
    PIN_INVALID(HttpStatus.UNAUTHORIZED),
    PIN_LOCKED(HttpStatus.LOCKED),
    PIN_REQUIRED(HttpStatus.FORBIDDEN),
    PIN_WEAK(HttpStatus.BAD_REQUEST),

    // --- account -------------------------------------------------------
    ACCOUNT_BLOCKED(HttpStatus.FORBIDDEN),
    PAYOUT_ACCOUNT_NOT_VERIFIED(HttpStatus.CONFLICT),
    PAYOUT_ACCOUNT_MISSING(HttpStatus.CONFLICT),
    PAYOUT_ACCOUNT_DUPLICATE(HttpStatus.CONFLICT),

    // --- QR / redemption -----------------------------------------------
    QR_NOT_FOUND(HttpStatus.NOT_FOUND),
    QR_ALREADY_REDEEMED(HttpStatus.CONFLICT),
    QR_EXPIRED(HttpStatus.GONE),
    QR_VOID(HttpStatus.CONFLICT),
    REDEMPTION_INVALID_STATE(HttpStatus.CONFLICT),
    RECEIPT_REQUIRED(HttpStatus.BAD_REQUEST),
    UPLOAD_FAILED(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}