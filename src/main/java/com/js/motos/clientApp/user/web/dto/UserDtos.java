package com.js.motos.clientApp.user.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.js.motos.clientApp.user.domain.PayoutStatus;
import com.js.motos.clientApp.user.domain.PayoutType;
import com.js.motos.clientApp.user.domain.UserRole;

/** Profile ("about me") and payout/bank-detail payloads. */
public final class UserDtos {

    /** {@code name@bank} — the standard UPI virtual payment address shape. */
    public static final String UPI_PATTERN = "^[a-zA-Z0-9._-]{2,64}@[a-zA-Z]{2,32}$";

    /** 4 letters, a literal 0, then 6 alphanumerics. */
    public static final String IFSC_PATTERN = "^[A-Z]{4}0[A-Z0-9]{6}$";

    private UserDtos() {
    }

    // --- profile ---------------------------------------------------------

    public record Profile(
            UUID id,
            String email,
            String fullName,
            String phone,
            String avatarUrl,
            UserRole role,
            boolean emailVerified,
            boolean pinSet,
            boolean hasVerifiedPayoutAccount,
            Instant memberSince,
            Stats stats) {
    }

    /** Headline numbers for the "about me" screen. */
    public record Stats(
            BigDecimal totalEarned,
            long redemptionsPaid,
            long redemptionsPending) {
    }

    public record UpdateProfile(
            @Size(max = 150) String fullName,
            @Pattern(regexp = "^$|^[0-9+\\-\\s]{7,20}$", message = "Enter a valid phone number")
            @Size(max = 20) String phone,
            @Size(max = 500) String avatarUrl) {
    }

    // --- payout accounts -------------------------------------------------

    public record PayoutAccount(
            UUID id,
            PayoutType type,
            String accountHolderName,
            String upiId,
            /** Masked, e.g. {@code HDFC Bank ****4321}. Full number is never returned. */
            String maskedAccount,
            String ifsc,
            String bankName,
            PayoutStatus status,
            boolean primary,
            String rejectionReason,
            Instant verifiedAt,
            Instant createdAt) {
    }

    public record CreateUpiAccount(
            @NotBlank @Pattern(regexp = UPI_PATTERN, message = "Enter a valid UPI ID, e.g. name@bank")
            @Size(max = 120) String upiId,
            @NotBlank @Size(max = 150) String accountHolderName,
            boolean makePrimary) {
    }

    public record CreateBankAccount(
            @NotBlank @Pattern(regexp = "^\\d{9,18}$", message = "Account number must be 9-18 digits")
            String accountNumber,
            @NotBlank String confirmAccountNumber,
            @NotBlank @Pattern(regexp = IFSC_PATTERN, message = "Enter a valid IFSC code")
            String ifsc,
            @Size(max = 120) String bankName,
            @NotBlank @Size(max = 150) String accountHolderName,
            boolean makePrimary) {

        /** Catches typos before an unverifiable account reaches an admin's queue. */
        @JsonIgnore
        @AssertTrue(message = "Account numbers do not match")
        public boolean isAccountNumberConfirmed() {
            return accountNumber != null && accountNumber.equals(confirmAccountNumber);
        }
    }

    // --- admin review ----------------------------------------------------

    public record RejectPayoutAccount(
            @NotBlank @Size(max = 500) String reason) {
    }

    public record AdminPayoutAccount(
            PayoutAccount account,
            UUID userId,
            String userEmail,
            String userName) {
    }
}