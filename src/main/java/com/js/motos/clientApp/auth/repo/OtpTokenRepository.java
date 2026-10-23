package com.js.motos.clientApp.auth.repo;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.js.motos.clientApp.auth.domain.OtpPurpose;
import com.js.motos.clientApp.auth.domain.OtpToken;

public interface OtpTokenRepository extends JpaRepository<OtpToken, UUID> {

    /** Most recent unconsumed OTP for this email+purpose — the one to verify against. */
    @Query("""
            SELECT o FROM OtpToken o
            WHERE lower(o.email) = lower(:email)
              AND o.purpose = :purpose
              AND o.consumedAt IS NULL
            ORDER BY o.createdAt DESC
            LIMIT 1
            """)
    Optional<OtpToken> findLatestActive(@Param("email") String email,
                                        @Param("purpose") OtpPurpose purpose);

    /** Most recent OTP regardless of state, used for the resend cooldown check. */
    @Query("""
            SELECT o FROM OtpToken o
            WHERE lower(o.email) = lower(:email) AND o.purpose = :purpose
            ORDER BY o.createdAt DESC
            LIMIT 1
            """)
    Optional<OtpToken> findLatest(@Param("email") String email,
                                  @Param("purpose") OtpPurpose purpose);

    @Query("""
            SELECT count(o) FROM OtpToken o
            WHERE lower(o.email) = lower(:email) AND o.createdAt > :since
            """)
    long countRecentForEmail(@Param("email") String email, @Param("since") Instant since);

    @Query("SELECT count(o) FROM OtpToken o WHERE o.requestIp = :ip AND o.createdAt > :since")
    long countRecentForIp(@Param("ip") String ip, @Param("since") Instant since);

    /** Invalidates outstanding codes so only the newest one can be used. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OtpToken o SET o.consumedAt = CURRENT_TIMESTAMP
            WHERE lower(o.email) = lower(:email)
              AND o.purpose = :purpose
              AND o.consumedAt IS NULL
            """)
    int consumeAllFor(@Param("email") String email, @Param("purpose") OtpPurpose purpose);

    @Modifying
    @Query("DELETE FROM OtpToken o WHERE o.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}