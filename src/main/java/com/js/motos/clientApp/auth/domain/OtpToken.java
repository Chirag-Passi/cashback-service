package com.js.motos.clientApp.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * A single issued OTP. The code itself is never stored — only its BCrypt hash,
 * so a database leak doesn't hand over live login codes.
 */
@Entity
@Table(name = "otp_tokens")
@Getter
@Setter
public class OtpToken {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private OtpPurpose purpose;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "request_ip", length = 45)
    private String requestIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isUsable() {
        return !isConsumed() && !isExpired();
    }
}