package com.js.motos.clientApp.qr.domain;

public enum QrCodeStatus {
    /** Printed and redeemable. */
    ACTIVE,
    /** Already claimed by a user; a redemption row exists. */
    REDEEMED,
    /** Past its expiry date. */
    EXPIRED,
    /** Cancelled by an admin (misprint, lost sheet, fraud). */
    VOID
}