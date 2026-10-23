package com.js.motos.clientApp.admin.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.audit.AuditService;
import com.js.motos.clientApp.auth.repo.RefreshTokenRepository;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.redemption.repo.RedemptionRepository;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.domain.UserRole;
import com.js.motos.clientApp.user.domain.UserStatus;
import com.js.motos.clientApp.user.repo.UserRepository;
import com.js.motos.clientApp.user.service.UserMapper;
import com.js.motos.clientApp.user.repo.PayoutAccountRepository;
import com.js.motos.clientApp.user.web.dto.UserDtos;

/** Admin-side user management. */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository userRepository;
    private final PayoutAccountRepository payoutAccountRepository;
    private final RedemptionRepository redemptionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;

    public AdminUserService(UserRepository userRepository,
                            PayoutAccountRepository payoutAccountRepository,
                            RedemptionRepository redemptionRepository,
                            RefreshTokenRepository refreshTokenRepository,
                            AuditService auditService) {
        this.userRepository = userRepository;
        this.payoutAccountRepository = payoutAccountRepository;
        this.redemptionRepository = redemptionRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserView> search(String query, UserStatus status, Pageable pageable) {
        return userRepository.search(query, status, pageable).map(this::toView);
    }

    @Transactional(readOnly = true)
    public AdminUserDetail detail(UUID userId) {
        User user = load(userId);
        List<UserDtos.PayoutAccount> accounts =
                payoutAccountRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(UserMapper::toDto)
                        .toList();

        return new AdminUserDetail(toView(user), accounts, user.getPhone(), user.getLastLoginAt());
    }

    /** Blocks the account and kills every live session in the same transaction. */
    @Transactional
    public AdminUserView block(UUID adminId, UUID userId, String reason) {
        User user = load(userId);

        if (user.isAdmin()) {
            // Without this an admin could lock the whole team out of the console.
            throw new ApiException(ErrorCode.FORBIDDEN, "Admin accounts cannot be blocked.");
        }
        if (adminId.equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You cannot block your own account.");
        }

        user.setStatus(UserStatus.BLOCKED);
        userRepository.save(user);
        int revoked = refreshTokenRepository.revokeAllForUser(userId, "USER_BLOCKED");

        log.info("Admin {} blocked user {} ({} sessions revoked)", adminId, userId, revoked);
        auditService.record(adminId, "USER_BLOCKED", "User", userId,
                Map.of("reason", String.valueOf(reason), "sessionsRevoked", revoked));

        return toView(user);
    }

    @Transactional
    public AdminUserView unblock(UUID adminId, UUID userId) {
        User user = load(userId);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        auditService.record(adminId, "USER_UNBLOCKED", "User", userId, Map.of());
        return toView(user);
    }

    private User load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private AdminUserView toView(User user) {
        return new AdminUserView(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.hasPin(),
                !payoutAccountRepository.findVerified(user.getId()).isEmpty(),
                redemptionRepository.totalEarnedBy(user.getId()),
                user.getCreatedAt());
    }

    public record AdminUserView(
            UUID id,
            String email,
            String fullName,
            UserRole role,
            UserStatus status,
            boolean emailVerified,
            boolean pinSet,
            boolean hasVerifiedPayoutAccount,
            BigDecimal totalEarned,
            Instant createdAt) {
    }

    public record AdminUserDetail(
            AdminUserView user,
            List<UserDtos.PayoutAccount> payoutAccounts,
            String phone,
            Instant lastLoginAt) {
    }
}