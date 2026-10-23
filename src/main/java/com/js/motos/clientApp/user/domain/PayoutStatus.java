package com.js.motos.clientApp.user.domain;

public enum PayoutStatus {
    /** Submitted by the user, awaiting admin review. */
    PENDING,
    /** Admin confirmed the VPA/account belongs to this user. Payouts allowed. */
    VERIFIED,
    /** Admin rejected it; the user must correct and resubmit. */
    REJECTED
}