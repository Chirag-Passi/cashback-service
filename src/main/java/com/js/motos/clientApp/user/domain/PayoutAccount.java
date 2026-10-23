package com.js.motos.clientApp.user.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.js.motos.clientApp.common.BaseEntity;
import com.js.motos.clientApp.config.EncryptedStringConverter;

import lombok.Getter;
import lombok.Setter;

/**
 * Where a user's cashback gets sent. Admins must verify an account before any
 * redemption can be paid out to it.
 */
@Entity
@Table(name = "payout_accounts")
@Getter
@Setter
public class PayoutAccount extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private PayoutType type;

    @Column(name = "account_holder_name", nullable = false, length = 150)
    private String accountHolderName;

    @Column(name = "upi_id", length = 120)
    private String upiId;

    /** Encrypted at rest; never returned to clients in full. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "bank_account_number", length = 512)
    private String bankAccountNumber;

    /** Plaintext last 4 digits, so the UI can display a masked account. */
    @Column(name = "bank_account_last4", length = 4)
    private String bankAccountLast4;

    @Column(name = "ifsc", length = 11)
    private String ifsc;

    @Column(name = "bank_name", length = 120)
    private String bankName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayoutStatus status = PayoutStatus.PENDING;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    public boolean isVerified() {
        return status == PayoutStatus.VERIFIED;
    }

    /** Masked, display-safe rendering of the payout target. */
    public String displayLabel() {
        if (type == PayoutType.UPI) {
            return upiId;
        }
        return (bankName == null ? "Bank" : bankName) + " ****" + bankAccountLast4;
    }
}