package com.js.motos.clientApp.auth.domain;

public enum OtpPurpose {
    /** Passwordless sign-in / sign-up by email. */
    LOGIN,
    /** Confirming ownership of a new email address. */
    EMAIL_CHANGE,
    /** Recovering access when the user forgot their 4-digit PIN. */
    PIN_RESET
}