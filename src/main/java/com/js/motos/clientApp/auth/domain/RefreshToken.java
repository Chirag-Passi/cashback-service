package com.js.motos.clientApp.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.js.motos.clientApp.user.domain.User;

import lombok.Getter;
import lombok.Setter;

/**
 * One logged-in device. This row <em>is</em> the session: revoking it is what
 * "log out completely" means, after which the PIN can no longer mint access
 * tokens and the user must sign in again with OTP or Google.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 of the opaque token; the raw value is only ever seen by the client. */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "device_name", length = 150)
    private String deviceName;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * When the PIN was last entered on this device. Drives the grace window —
     * once stale, a refresh yields only a PIN_REQUIRED token.
     */
    @Column(name = "pin_verified_at")
    private Instant pinVerifiedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 60)
    private String revokeReason;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }

    /** True when the PIN was entered recently enough to keep full trust. */
    public boolean isPinFresh(java.time.Duration window) {
        return pinVerifiedAt != null && pinVerifiedAt.isAfter(Instant.now().minus(window));
    }

    public void revoke(String reason) {
        this.revokedAt = Instant.now();
        this.revokeReason = reason;
    }
}