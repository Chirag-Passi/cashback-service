package com.js.motos.clientApp.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.auth.domain.OtpPurpose;
import com.js.motos.clientApp.auth.domain.OtpToken;
import com.js.motos.clientApp.auth.repo.OtpTokenRepository;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.Codes;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;
import com.js.motos.clientApp.mail.MailService;
import com.js.motos.clientApp.security.RateLimitService;

/**
 * Issues and verifies email one-time codes.
 *
 * <p>Codes are stored only as BCrypt hashes, superseded on every resend, capped
 * at a fixed number of verify attempts, and rate limited per email and per IP.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private final OtpTokenRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final RateLimitService rateLimiter;
    private final AppProperties properties;

    public OtpService(OtpTokenRepository repository,
                      PasswordEncoder passwordEncoder,
                      MailService mailService,
                      RateLimitService rateLimiter,
                      AppProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;

        if (properties.otp().devMode()) {
            log.warn("""
                    ============================================================
                     OTP DEV MODE IS ON. Codes are returned in API responses and
                     written to the log instead of being emailed.
                     This MUST NOT be enabled outside local development.
                    ============================================================""");
        }
    }

    /**
     * Generates, stores and emails a fresh code, invalidating any outstanding one.
     *
     * @return the plaintext code when dev mode is on, otherwise empty
     */
    @Transactional
    public Optional<String> request(String email, OtpPurpose purpose, String ip) {
        String normalized = normalize(email);
        AppProperties.Otp config = properties.otp();

        enforceCooldown(normalized, purpose, config.resendCooldown());
        enforceQuotas(normalized, ip, config);

        // Only the newest code may be used; older ones are burned immediately.
        repository.consumeAllFor(normalized, purpose);

        String code = Codes.numericOtp(config.length());

        OtpToken token = new OtpToken();
        token.setEmail(normalized);
        token.setCodeHash(passwordEncoder.encode(code));
        token.setPurpose(purpose);
        token.setExpiresAt(Instant.now().plus(config.ttl()));
        token.setRequestIp(ip);
        repository.save(token);

        if (config.devMode()) {
            log.warn("DEV MODE — OTP for {} ({}): {}", normalized, purpose, code);
            return Optional.of(code);
        }

        // Sent inside the transaction on purpose: if SMTP fails, the row rolls
        // back, so the user is not left with a burned cooldown and no code.
        mailService.sendOtp(normalized, code, (int) config.ttl().toMinutes());
        return Optional.empty();
    }

    /**
     * Checks a submitted code and consumes it on success.
     *
     * @throws ApiException if the code is missing, expired, over its attempt
     *                      budget, or simply wrong
     */
    @Transactional
    public void verify(String email, OtpPurpose purpose, String code) {
        String normalized = normalize(email);

        OtpToken token = repository.findLatestActive(normalized, purpose)
                .orElseThrow(() -> new ApiException(ErrorCode.OTP_INVALID,
                        "No active code for this email. Request a new one."));

        if (token.isExpired()) {
            throw new ApiException(ErrorCode.OTP_EXPIRED, "This code has expired. Request a new one.");
        }

        if (token.getAttempts() >= properties.otp().maxVerifyAttempts()) {
            // Burn it so an attacker cannot keep guessing against the same code.
            token.setConsumedAt(Instant.now());
            repository.save(token);
            throw new ApiException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,
                    "Too many incorrect attempts. Request a new code.");
        }

        if (!passwordEncoder.matches(code, token.getCodeHash())) {
            token.setAttempts(token.getAttempts() + 1);
            repository.save(token);

            int remaining = properties.otp().maxVerifyAttempts() - token.getAttempts();
            throw new ApiException(ErrorCode.OTP_INVALID,
                    "Incorrect code. " + Math.max(remaining, 0) + " attempt(s) remaining.");
        }

        token.setConsumedAt(Instant.now());
        repository.save(token);
        rateLimiter.reset("otp:email:" + normalized);
    }

    private void enforceCooldown(String email, OtpPurpose purpose, Duration cooldown) {
        repository.findLatest(email, purpose).ifPresent(latest -> {
            Instant nextAllowed = latest.getCreatedAt().plus(cooldown);
            if (nextAllowed.isAfter(Instant.now())) {
                long waitSeconds = Duration.between(Instant.now(), nextAllowed).toSeconds() + 1;
                throw new ApiException(ErrorCode.OTP_COOLDOWN,
                        "Please wait " + waitSeconds + " seconds before requesting another code.");
            }
        });
    }

    private void enforceQuotas(String email, String ip, AppProperties.Otp config) {
        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));

        // Durable, DB-backed counters: these survive restarts and extra instances.
        if (repository.countRecentForEmail(email, hourAgo) >= config.maxPerHourPerEmail()) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many codes requested for this email. Try again later.");
        }
        if (ip != null && repository.countRecentForIp(ip, hourAgo) >= config.maxPerHourPerIp()) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many requests from this device. Try again later.");
        }

        // Cheap in-memory guard that rejects bursts before they reach the DB.
        rateLimiter.check("otp:email:" + email, config.maxPerHourPerEmail(),
                Duration.ofHours(1), "Too many codes requested for this email. Try again later.");
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}