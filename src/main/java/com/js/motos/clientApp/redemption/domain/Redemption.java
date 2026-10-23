package com.js.motos.clientApp.redemption.domain;

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
import com.js.motos.clientApp.qr.domain.QrCode;
import com.js.motos.clientApp.user.domain.PayoutAccount;
import com.js.motos.clientApp.user.domain.PayoutType;
import com.js.motos.clientApp.user.domain.User;

import lombok.Getter;
import lombok.Setter;

/**
 * A user's claim against one QR code, and the admin's record of paying it.
 *
 * <p>Payout details are <em>snapshotted</em> at claim time. If the user later
 * edits their UPI ID, historical redemptions still show where the money actually
 * went — which is what an audit or a payment dispute needs.
 */
@Entity
@Table(name = "redemptions")
@Getter
@Setter
public class Redemption extends BaseEntity {

    /** Human-quotable identifier, e.g. {@code RDM-001042}. */
    @Column(name = "reference_no", nullable = false, length = 24, unique = true)
    private String referenceNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qr_code_id", nullable = false, unique = true)
    private QrCode qrCode;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RedemptionStatus status = RedemptionStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_account_id")
    private PayoutAccount payoutAccount;

    // --- payout snapshot, frozen at claim time --------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_type_snapshot", length = 10)
    private PayoutType payoutTypeSnapshot;

    @Column(name = "upi_id_snapshot", length = 120)
    private String upiIdSnapshot;

    @Column(name = "bank_last4_snapshot", length = 4)
    private String bankLast4Snapshot;

    @Column(name = "ifsc_snapshot", length = 11)
    private String ifscSnapshot;

    @Column(name = "holder_name_snapshot", length = 150)
    private String holderNameSnapshot;

    // --- workflow timestamps --------------------------------------------

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_by")
    private UUID paidBy;

    /** Bank/UPI transaction reference entered by the admin when marking paid. */
    @Column(name = "utr_reference", length = 50)
    private String utrReference;

    @Column(name = "receipt_url")
    private String receiptUrl;

    /** Storage-provider handle, kept so the file can be deleted or replaced. */
    @Column(name = "receipt_public_id", length = 200)
    private String receiptPublicId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "admin_notes")
    private String adminNotes;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public boolean isTerminal() {
        return status == RedemptionStatus.PAID || status == RedemptionStatus.REJECTED;
    }

    /** Copies the payout target onto this claim so later edits can't rewrite it. */
    public void snapshotPayout(PayoutAccount account) {
        this.payoutAccount = account;
        this.payoutTypeSnapshot = account.getType();
        this.upiIdSnapshot = account.getUpiId();
        this.bankLast4Snapshot = account.getBankAccountLast4();
        this.ifscSnapshot = account.getIfsc();
        this.holderNameSnapshot = account.getAccountHolderName();
    }
}