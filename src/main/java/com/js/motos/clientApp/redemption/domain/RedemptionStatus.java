package com.js.motos.clientApp.redemption.domain;

/**
 * Lifecycle of a cashback claim.
 *
 * <pre>
 *   PENDING ──approve──> APPROVED ──complete──> PAID
 *      │                    │
 *      └──── reject ────────┴──> REJECTED  (releases the QR code)
 * </pre>
 */
public enum RedemptionStatus {
    /** User scanned the code; awaiting admin review. */
    PENDING,
    /** Admin okayed it; payment is being made out-of-band via UPI. */
    APPROVED,
    /** Money sent, receipt uploaded, UTR recorded. Terminal. */
    PAID,
    /** Turned down with a reason. Terminal. */
    REJECTED
}