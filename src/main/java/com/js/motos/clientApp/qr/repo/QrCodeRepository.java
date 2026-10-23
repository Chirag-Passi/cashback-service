package com.js.motos.clientApp.qr.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.js.motos.clientApp.qr.domain.QrCode;
import com.js.motos.clientApp.qr.domain.QrCodeStatus;

public interface QrCodeRepository extends JpaRepository<QrCode, UUID> {

    Optional<QrCode> findByCode(String code);

    /**
     * Row-locking lookup used on the redemption path. Two concurrent scans of the
     * same sticker serialise here, so the second one sees status = REDEEMED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM QrCode q WHERE q.code = :code")
    Optional<QrCode> findByCodeForUpdate(@Param("code") String code);

    Page<QrCode> findAllByBatchId(UUID batchId, Pageable pageable);

    List<QrCode> findAllByBatchIdOrderByCreatedAtAsc(UUID batchId);

    Page<QrCode> findAllByStatus(QrCodeStatus status, Pageable pageable);

    long countByBatchIdAndStatus(UUID batchId, QrCodeStatus status);

    long countByStatus(QrCodeStatus status);

    boolean existsByCode(String code);

    /** Sweeps codes whose expiry has passed so listings stay accurate. */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE QrCode q SET q.status = 'EXPIRED'
            WHERE q.status = 'ACTIVE' AND q.expiresAt IS NOT NULL AND q.expiresAt < :now
            """)
    int markExpired(@Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE QrCode q SET q.status = 'VOID' WHERE q.batch.id = :batchId AND q.status = 'ACTIVE'")
    int voidActiveInBatch(@Param("batchId") UUID batchId);
}