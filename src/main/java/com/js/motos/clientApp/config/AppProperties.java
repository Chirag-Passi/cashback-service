package com.js.motos.clientApp.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed view of the {@code app.*} configuration tree.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("support@example.com") String supportEmail,
        @DefaultValue("Motos Rewards") String brandName,
        Jwt jwt,
        Refresh refresh,
        Otp otp,
        Pin pin,
        Google google,
        Cloudinary cloudinary,
        Storage storage,
        Security security,
        Admin admin) {

    public record Jwt(
            String secret,
            @DefaultValue("motos-clientapp") String issuer,
            @DefaultValue("PT15M") Duration accessTokenTtl) {
    }

    public record Refresh(
            @DefaultValue("P30D") Duration ttl,
            @DefaultValue("5") int maxSessionsPerUser) {
    }

    public record Otp(
            @DefaultValue("6") int length,
            @DefaultValue("PT10M") Duration ttl,
            @DefaultValue("5") int maxVerifyAttempts,
            @DefaultValue("PT60S") Duration resendCooldown,
            @DefaultValue("5") int maxPerHourPerEmail,
            @DefaultValue("20") int maxPerHourPerIp,
            @DefaultValue("false") boolean devMode) {
    }

    public record Pin(
            @DefaultValue("4") int length,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("PT15M") Duration lockoutDuration,
            @DefaultValue("PT30M") Duration sessionTtl) {
    }

    public record Google(@DefaultValue("") List<String> clientIds) {
    }

    public record Cloudinary(
            @DefaultValue("") String cloudName,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String apiSecret,
            @DefaultValue("motos/receipts") String folder,
            @DefaultValue("false") boolean enabled) {
    }

    public record Storage(@DefaultValue("./uploads") String localDir) {
    }

    public record Security(
            String encryptionKey,
            @DefaultValue("*") List<String> corsAllowedOrigins) {
    }

    public record Admin(@DefaultValue("") String bootstrapEmail) {
    }

    /** Largest cashback a single QR code may carry; guards against typo'd batches. */
    public static final BigDecimal MAX_QR_AMOUNT = new BigDecimal("100000.00");
}