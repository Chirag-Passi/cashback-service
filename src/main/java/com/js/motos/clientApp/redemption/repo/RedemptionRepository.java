package com.js.motos.clientApp.redemption.repo;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.js.motos.clientApp.redemption.domain.Redemption;
import com.js.motos.clientApp.redemption.domain.RedemptionStatus;

public interface RedemptionRepository extends JpaRepository<Redemption, UUID> {

    Optional<Redemption> findByReferenceNo(String referenceNo);

    Optional<Redemption> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByQrCodeId(UUID qrCodeId);

    @Query("""
            SELECT r FROM Redemption r
            WHERE r.user.id = :userId
              AND (:status IS NULL OR r.status = :status)
            ORDER BY r.requestedAt DESC
            """)
    Page<Redemption> findForUser(@Param("userId") UUID userId,
                                 @Param("status") RedemptionStatus status,
                                 Pageable pageable);

    @Query("""
            SELECT r FROM Redemption r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:q IS NULL OR lower(r.referenceNo) LIKE lower(concat('%', :q, '%'))
                             OR lower(r.user.email) LIKE lower(concat('%', :q, '%'))
                             OR lower(r.upiIdSnapshot) LIKE lower(concat('%', :q, '%')))
            ORDER BY r.requestedAt DESC
            """)
    Page<Redemption> searchForAdmin(@Param("q") String query,
                                    @Param("status") RedemptionStatus status,
                                    Pageable pageable);

    long countByStatus(RedemptionStatus status);

    @Query("SELECT coalesce(sum(r.amount), 0) FROM Redemption r WHERE r.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") RedemptionStatus status);

    @Query("SELECT coalesce(sum(r.amount), 0) FROM Redemption r WHERE r.user.id = :userId AND r.status = 'PAID'")
    BigDecimal totalEarnedBy(@Param("userId") UUID userId);

    /** Allocates the next human-readable reference number. */
    @Query(value = "SELECT nextval('redemption_ref_seq')", nativeQuery = true)
    long nextReferenceSequence();
}