package com.js.motos.clientApp.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.stereotype.Service;

import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;

/**
 * In-memory fixed-window rate limiting for the unauthenticated auth endpoints.
 *
 * <p>Deliberately process-local: it needs no Redis, which keeps the deployment
 * inside free tiers. The trade-off is that limits are per-instance, so if you
 * ever scale past one instance, move the durable counters (OTP-per-email,
 * OTP-per-IP — already persisted in {@code otp_tokens}) to the database checks
 * in {@code OtpService} and treat this class as a cheap first line only.
 */
@Service
public class RateLimitService {

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    /**
     * Records one hit against {@code key} and throws once {@code limit} is exceeded
     * within the window.
     */
    public void check(String key, int limit, Duration window, String message) {
        // Bucketing by window start gives fixed-window semantics without a
        // per-key scheduler; the cache entry simply ages out afterwards.
        long bucket = System.currentTimeMillis() / window.toMillis();
        String bucketKey = key + '#' + bucket;

        AtomicInteger counter = counters.get(bucketKey, k -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            throw new ApiException(ErrorCode.RATE_LIMITED, message);
        }
    }

    /** Clears a key's counters — called after a successful login. */
    public void reset(String key) {
        counters.asMap().keySet().removeIf(k -> k.startsWith(key + '#'));
    }
}