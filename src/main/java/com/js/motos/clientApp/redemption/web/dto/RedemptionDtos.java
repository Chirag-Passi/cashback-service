package com.js.motos.clientApp.redemption.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.js.motos.clientApp.redemption.domain.RedemptionStatus;
import com.js.motos.clientApp.user.domain.PayoutType;

public final class RedemptionDtos {

    private RedemptionDtos() {
    }

    // --- user ------------------------------------------------------------

    /** @param scan the decoded QR content: either the bare code or the deep link */
    public record Claim(
            @NotBlank @Size(max = 500) String scan) {
    }

    public record Item(
            UUID id,
            String referenceNo,
            BigDecimal amount,
            RedemptionStatus status,
            PayoutType payoutType,
            /** Masked payout target as it stood when the claim was made. */
            String payoutTarget,
            Instant requestedAt,
            Instant approvedAt,
            Instant paidAt,
            String utrReference,
            String receiptUrl,
            String rejectionReason) {
    }

    // --- admin -----------------------------------------------------------

    public record AdminItem(
            Item redemption,
            UUID userId,
            String userEmail,
            String userName,
            String userPhone,
            /** Unmasked UPI ID so the admin can actually pay it. */
            String payoutUpiId,
            String payoutAccountHolder,
            String payoutIfsc,
            String payoutBankLast4,
            String adminNotes,
            String qrCode) {
    }

    public record Approve(
            @Size(max = 1000) String notes) {
    }

    public record Reject(
            @NotBlank @Size(max = 500) String reason,
            /**
             * Whether to return the QR code to circulation so the user can claim it
             * again after fixing the problem. Leave false for suspected fraud.
             */
            Boolean releaseCode) {
    }

    /** Dashboard counters for the admin home screen. */
    public record Dashboard(
            long pendingRedemptions,
            long approvedRedemptions,
            long paidRedemptions,
            long rejectedRedemptions,
            BigDecimal pendingAmount,
            BigDecimal paidAmount,
            long pendingPayoutAccounts,
            long activeCodes,
            long redeemedCodes) {
    }
}