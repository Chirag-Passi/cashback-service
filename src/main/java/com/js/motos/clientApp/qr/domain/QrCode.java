package com.js.motos.clientApp.qr.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.js.motos.clientApp.common.BaseEntity;

import lombok.Getter;
import lombok.Setter;

/**
 * A single physical scratch/sticker code worth a fixed cashback amount.
 *
 * <p>The {@code @Version} column plus the UNIQUE constraint on
 * {@code redemptions.qr_code_id} together guarantee that two users scanning the
 * same code at the same instant cannot both be paid.
 */
@Entity
@Table(name = "qr_codes")
@Getter
@Setter
public class QrCode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private QrBatch batch;

    /** The opaque value printed on the sticker; ~78 bits of entropy. */
    @Column(name = "code", nullable = false, length = 32, unique = true)
    private String code;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QrCodeStatus status = QrCodeStatus.ACTIVE;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "redeemed_by")
    private UUID redeemedBy;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    /** Redeemable right now: still ACTIVE and not past expiry. */
    public boolean isRedeemable() {
        return status == QrCodeStatus.ACTIVE && !isExpired();
    }
}