package com.js.motos.clientApp.notification.domain;

public enum NotificationType {
    // --- to admins ------------------------------------------------------
    REDEMPTION_REQUESTED,
    PAYOUT_ACCOUNT_SUBMITTED,

    // --- to users -------------------------------------------------------
    REDEMPTION_APPROVED,
    REDEMPTION_PAID,
    REDEMPTION_REJECTED,
    PAYOUT_ACCOUNT_VERIFIED,
    PAYOUT_ACCOUNT_REJECTED
}