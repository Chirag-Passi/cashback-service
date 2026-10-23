package com.js.motos.clientApp.security;

/**
 * How much a given access token is trusted to do.
 *
 * <p>Every access token carries exactly one of these in its {@code scp} claim,
 * surfaced to Spring Security as a {@code SCOPE_*} authority.
 */
public enum TokenScope {

    /**
     * Identity proven (OTP or Google) but no PIN exists yet. The holder may only
     * read their own profile and set a PIN — no money movement, no payout edits.
     */
    PIN_SETUP,

    /**
     * A valid session whose PIN grace window has lapsed. Useless for everything
     * except unlocking or logging out; this is what stops a stolen refresh token
     * from silently becoming a full session.
     */
    PIN_REQUIRED,

    /** PIN verified recently (or fresh interactive login). Full access. */
    FULL;

    public String authority() {
        return "SCOPE_" + name();
    }
}