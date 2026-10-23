package com.js.motos.clientApp.user.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.audit.AuditService;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.redemption.domain.RedemptionStatus;
import com.js.motos.clientApp.redemption.repo.RedemptionRepository;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.repo.PayoutAccountRepository;
import com.js.motos.clientApp.user.repo.UserRepository;
import com.js.motos.clientApp.user.web.dto.UserDtos;

/** Profile reads and edits — the "about me" screen. */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PayoutAccountRepository payoutAccountRepository;
    private final RedemptionRepository redemptionRepository;
    private final AuditService auditService;

    public UserService(UserRepository userRepository,
                       PayoutAccountRepository payoutAccountRepository,
                       RedemptionRepository redemptionRepository,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.payoutAccountRepository = payoutAccountRepository;
        this.redemptionRepository = redemptionRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public UserDtos.Profile getProfile(UUID userId) {
        return toProfile(load(userId));
    }

    @Transactional
    public UserDtos.Profile updateProfile(UUID userId, UserDtos.UpdateProfile request) {
        User user = load(userId);

        // Null means "leave unchanged"; blank means "clear".
        if (request.fullName() != null) {
            user.setFullName(blankToNull(request.fullName()));
        }
        if (request.phone() != null) {
            user.setPhone(blankToNull(request.phone()));
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(blankToNull(request.avatarUrl()));
        }

        User saved = userRepository.save(user);
        auditService.record(userId, "PROFILE_UPDATED", "User", userId, Map.of());
        return toProfile(saved);
    }

    private UserDtos.Profile toProfile(User user) {
        UUID userId = user.getId();

        UserDtos.Stats stats = new UserDtos.Stats(
                redemptionRepository.totalEarnedBy(userId),
                redemptionRepository.findForUser(userId, RedemptionStatus.PAID,
                        org.springframework.data.domain.Pageable.ofSize(1)).getTotalElements(),
                redemptionRepository.findForUser(userId, RedemptionStatus.PENDING,
                        org.springframework.data.domain.Pageable.ofSize(1)).getTotalElements());

        return new UserDtos.Profile(
                userId,
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getAvatarUrl(),
                user.getRole(),
                user.isEmailVerified(),
                user.hasPin(),
                !payoutAccountRepository.findVerified(userId).isEmpty(),
                user.getCreatedAt(),
                stats);
    }

    public User load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}