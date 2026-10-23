package com.js.motos.clientApp.qr.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.js.motos.clientApp.qr.domain.QrCodeStatus;

public final class QrDtos {

    private QrDtos() {
    }

    /** Admin request to mint a print run of codes, all worth the same amount. */
    public record CreateBatch(
            @NotBlank @Size(max = 150) String label,

            @NotNull
            @DecimalMin(value = "1.00", message = "Amount must be at least 1")
            @DecimalMax(value = "100000.00", message = "Amount must be 100000 or less")
            @Digits(integer = 8, fraction = 2)
            BigDecimal amount,

            @Min(value = 1, message = "Generate at least 1 code")
            @Max(value = 5000, message = "Generate at most 5000 codes per batch")
            int quantity,

            @Future(message = "Expiry must be in the future")
            Instant expiresAt,

            @Size(max = 1000) String notes) {
    }

    public record BatchSummary(
            UUID id,
            String label,
            BigDecimal amount,
            int quantity,
            Instant expiresAt,
            String notes,
            Instant createdAt,
            BatchStats stats) {
    }

    public record BatchStats(
            long active,
            long redeemed,
            long expired,
            long voided,
            BigDecimal totalFaceValue,
            BigDecimal redeemedValue) {
    }

    public record QrCodeItem(
            UUID id,
            String code,
            BigDecimal amount,
            QrCodeStatus status,
            Instant expiresAt,
            Instant redeemedAt,
            /** What gets encoded into the printed QR image. */
            String payload) {
    }

    /**
     * What the app shows after scanning but before the user confirms the claim.
     * Deliberately reveals nothing beyond validity and amount.
     */
    public record ScanPreview(
            boolean claimable,
            BigDecimal amount,
            QrCodeStatus status,
            String message,
            boolean payoutAccountReady) {
    }

    public record VoidRequest(
            @NotBlank @Size(max = 500) String reason) {
    }
}