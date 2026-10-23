package com.js.motos.clientApp.auth.web.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.js.motos.clientApp.security.TokenScope;
import com.js.motos.clientApp.user.domain.UserRole;

/** Request and response payloads for the authentication endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    // --- requests --------------------------------------------------------

    public record RequestOtp(
            @NotBlank @Email @Size(max = 320) String email) {
    }

    public record VerifyOtp(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "\\d{4,8}", message = "Code must be numeric") String code,
            @Size(max = 128) String deviceId,
            @Size(max = 150) String deviceName) {
    }

    public record GoogleLogin(
            @NotBlank String idToken,
            @Size(max = 128) String deviceId,
            @Size(max = 150) String deviceName) {
    }

    public record SetPin(
            @NotBlank @Pattern(regexp = "\\d{4}", message = "PIN must be exactly 4 digits") String pin) {
    }

    public record ChangePin(
            @NotBlank @Pattern(regexp = "\\d{4}", message = "PIN must be exactly 4 digits") String currentPin,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "PIN must be exactly 4 digits") String newPin) {
    }

    public record Unlock(
            @NotBlank String refreshToken,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "PIN must be exactly 4 digits") String pin) {
    }

    public record Refresh(
            @NotBlank String refreshToken) {
    }

    public record Logout(
            String refreshToken,
            boolean allDevices) {
    }

    public record ResetPinRequest(
            @NotBlank @Email @Size(max = 320) String email) {
    }

    public record ResetPinConfirm(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "\\d{4,8}") String code,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "PIN must be exactly 4 digits") String newPin) {
    }

    // --- responses -------------------------------------------------------

    public record OtpRequested(
            String message,
            long expiresInSeconds,
            /** Populated only when OTP dev mode is enabled. Never set in production. */
            String devCode) {
    }

    /**
     * Issued after any successful authentication step.
     *
     * @param scope       what this access token may do; {@code PIN_SETUP} means the
     *                    client must send the user to the create-PIN screen
     * @param pinSet      whether the account already has a PIN
     * @param refreshToken returned only when a new session is created; null on refresh
     */
    public record Session(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            Instant expiresAt,
            TokenScope scope,
            String refreshToken,
            boolean pinSet,
            UserSummary user) {
    }

    public record UserSummary(
            UUID id,
            String email,
            String fullName,
            String avatarUrl,
            UserRole role,
            boolean emailVerified) {
    }

    public record DeviceSession(
            UUID id,
            String deviceName,
            String ip,
            Instant issuedAt,
            Instant lastUsedAt,
            Instant expiresAt,
            boolean current) {
    }

    public record MessageResponse(String message) {
    }
}