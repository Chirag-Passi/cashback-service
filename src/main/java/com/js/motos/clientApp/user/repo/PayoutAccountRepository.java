package com.js.motos.clientApp.user.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.js.motos.clientApp.user.domain.PayoutAccount;
import com.js.motos.clientApp.user.domain.PayoutStatus;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, UUID> {

    List<PayoutAccount> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<PayoutAccount> findByIdAndUserId(UUID id, UUID userId);

    @Query("SELECT p FROM PayoutAccount p WHERE p.user.id = :userId AND p.primary = true")
    Optional<PayoutAccount> findPrimary(@Param("userId") UUID userId);

    /**
     * The account a payout should target: the primary one if it is verified,
     * otherwise the oldest verified account.
     */
    @Query("""
            SELECT p FROM PayoutAccount p
            WHERE p.user.id = :userId AND p.status = 'VERIFIED'
            ORDER BY p.primary DESC, p.createdAt ASC
            """)
    List<PayoutAccount> findVerified(@Param("userId") UUID userId);

    @Query("""
            SELECT p FROM PayoutAccount p
            WHERE (:status IS NULL OR p.status = :status)
            ORDER BY p.createdAt DESC
            """)
    Page<PayoutAccount> findByStatus(@Param("status") PayoutStatus status, Pageable pageable);

    /** Clears the primary flag before promoting a different account. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PayoutAccount p SET p.primary = false WHERE p.user.id = :userId AND p.primary = true")
    void clearPrimaryFor(@Param("userId") UUID userId);

    /** Guards against two users claiming the same verified VPA. */
    @Query("""
            SELECT count(p) > 0 FROM PayoutAccount p
            WHERE lower(p.upiId) = lower(:upiId)
              AND p.status = 'VERIFIED'
              AND p.user.id <> :userId
            """)
    boolean upiClaimedByAnotherUser(@Param("upiId") String upiId, @Param("userId") UUID userId);

    long countByStatus(PayoutStatus status);
}