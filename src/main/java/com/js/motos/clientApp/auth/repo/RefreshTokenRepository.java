package com.js.motos.clientApp.auth.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.js.motos.clientApp.auth.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("""
            SELECT r FROM RefreshToken r
            WHERE r.user.id = :userId AND r.revokedAt IS NULL AND r.expiresAt > CURRENT_TIMESTAMP
            ORDER BY r.issuedAt ASC
            """)
    List<RefreshToken> findActiveForUser(@Param("userId") UUID userId);

    /** Full logout across every device — also used when a PIN is brute-forced. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken r
            SET r.revokedAt = CURRENT_TIMESTAMP, r.revokeReason = :reason
            WHERE r.user.id = :userId AND r.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("reason") String reason);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}