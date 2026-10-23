package com.js.motos.clientApp.config;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.auth.repo.OtpTokenRepository;
import com.js.motos.clientApp.auth.repo.RefreshTokenRepository;

/**
 * Nightly housekeeping. Keeps the auth tables from growing without bound, which
 * matters on a free-tier database with a hard storage cap.
 */
@Component
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    /** Expired rows are kept this long so they still show up in incident forensics. */
    private static final Duration RETENTION = Duration.ofDays(7);

    private final OtpTokenRepository otpTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public CleanupJob(OtpTokenRepository otpTokenRepository,
                      RefreshTokenRepository refreshTokenRepository) {
        this.otpTokenRepository = otpTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        Instant cutoff = Instant.now().minus(RETENTION);

        int otps = otpTokenRepository.deleteExpiredBefore(cutoff);
        int sessions = refreshTokenRepository.deleteExpiredBefore(cutoff);

        if (otps > 0 || sessions > 0) {
            log.info("Cleanup removed {} expired OTPs and {} expired sessions", otps, sessions);
        }
    }
}