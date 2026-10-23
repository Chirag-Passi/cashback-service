package com.js.motos.clientApp.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.js.motos.clientApp.common.BaseEntity;

import lombok.Getter;
import lombok.Setter;

/**
 * An app account. Email is the identity key: OTP login and Google Sign-In both
 * resolve to the same row, so a user who starts with one can later use the other.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "full_name", length = 150)
    private String fullName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /** Google's stable subject identifier; set on first Google Sign-In. */
    @Column(name = "google_subject", length = 64)
    private String googleSubject;

    /** BCrypt hash of the 4-digit PIN. Null until the user completes setup. */
    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @Column(name = "pin_updated_at")
    private Instant pinUpdatedAt;

    @Column(name = "pin_failed_attempts", nullable = false)
    private int pinFailedAttempts = 0;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public boolean hasPin() {
        return pinHash != null;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** True while a PIN lockout window is still in effect. */
    public boolean isPinLocked() {
        return pinLockedUntil != null && pinLockedUntil.isAfter(Instant.now());
    }
}