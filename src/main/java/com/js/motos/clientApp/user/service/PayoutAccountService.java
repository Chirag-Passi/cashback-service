package com.js.motos.clientApp.user.service;

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
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.mail.MailService;
import com.js.motos.clientApp.notification.NotificationService;
import com.js.motos.clientApp.notification.domain.NotificationType;
import com.js.motos.clientApp.user.domain.PayoutAccount;
import com.js.motos.clientApp.user.domain.PayoutStatus;
import com.js.motos.clientApp.user.domain.PayoutType;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.repo.PayoutAccountRepository;
import com.js.motos.clientApp.user.repo.UserRepository;
import com.js.motos.clientApp.user.web.dto.UserDtos;

/**
 * Manages where a user's cashback is sent, and the admin review that must happen
 * before any money moves.
 *
 * <p>Every submission lands in {@code PENDING}. Editing a verified account is not
 * allowed — the user adds a new one, which goes back through review. That keeps
 * an approved payout target from being silently swapped after the fact.
 */
@Service
public class PayoutAccountService {

    private static final Logger log = LoggerFactory.getLogger(PayoutAccountService.class);

    private final PayoutAccountRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final AuditService auditService;

    public PayoutAccountService(PayoutAccountRepository repository,
                                UserRepository userRepository,
                                NotificationService notificationService,
                                MailService mailService,
                                AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.mailService = mailService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<UserDtos.PayoutAccount> listForUser(UUID userId) {
        return repository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(UserMapper::toDto)
                .toList();
    }

    @Transactional
    public UserDtos.PayoutAccount addUpi(UUID userId, UserDtos.CreateUpiAccount request) {
        User user = loadUser(userId);
        String upiId = request.upiId().trim().toLowerCase();

        if (repository.upiClaimedByAnotherUser(upiId, userId)) {
            throw new ApiException(ErrorCode.PAYOUT_ACCOUNT_DUPLICATE,
                    "This UPI ID is already verified on another account.");
        }
        boolean duplicateForUser = repository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .anyMatch(existing -> upiId.equalsIgnoreCase(existing.getUpiId())
                        && existing.getStatus() != PayoutStatus.REJECTED);
        if (duplicateForUser) {
            throw new ApiException(ErrorCode.PAYOUT_ACCOUNT_DUPLICATE,
                    "You have already added this UPI ID.");
        }

        PayoutAccount account = new PayoutAccount();
        account.setUser(user);
        account.setType(PayoutType.UPI);
        account.setUpiId(upiId);
        account.setAccountHolderName(request.accountHolderName().trim());

        return save(account, user, request.makePrimary());
    }

    @Transactional
    public UserDtos.PayoutAccount addBank(UUID userId, UserDtos.CreateBankAccount request) {
        User user = loadUser(userId);
        String accountNumber = request.accountNumber().trim();

        PayoutAccount account = new PayoutAccount();
        account.setUser(user);
        account.setType(PayoutType.BANK);
        account.setBankAccountNumber(accountNumber);
        account.setBankAccountLast4(accountNumber.substring(accountNumber.length() - 4));
        account.setIfsc(request.ifsc().trim().toUpperCase());
        account.setBankName(request.bankName() == null ? null : request.bankName().trim());
        account.setAccountHolderName(request.accountHolderName().trim());

        return save(account, user, request.makePrimary());
    }

    private UserDtos.PayoutAccount save(PayoutAccount account, User user, boolean makePrimary) {
        boolean first = repository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).isEmpty();
        if (makePrimary || first) {
            repository.clearPrimaryFor(user.getId());
            account.setPrimary(true);
        }

        PayoutAccount saved = repository.save(account);

        notificationService.notifyAdmins(
                NotificationType.PAYOUT_ACCOUNT_SUBMITTED,
                "Payout account awaiting verification",
                user.getEmail() + " submitted " + saved.displayLabel() + " for verification.",
                Map.of("payoutAccountId", saved.getId().toString(), "userId", user.getId().toString()));

        auditService.record(user.getId(), "PAYOUT_ACCOUNT_ADDED", "PayoutAccount", saved.getId(),
                Map.of("type", saved.getType().name()));

        return UserMapper.toDto(saved);
    }

    @Transactional
    public UserDtos.PayoutAccount makePrimary(UUID userId, UUID accountId) {
        PayoutAccount account = repository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Payout account"));

        if (!account.isVerified()) {
            throw new ApiException(ErrorCode.PAYOUT_ACCOUNT_NOT_VERIFIED,
                    "Only a verified account can be made primary.");
        }

        repository.clearPrimaryFor(userId);
        account.setPrimary(true);
        return UserMapper.toDto(repository.save(account));
    }

    @Transactional
    public void delete(UUID userId, UUID accountId) {
        PayoutAccount account = repository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("Payout account"));
        repository.delete(account);
        auditService.record(userId, "PAYOUT_ACCOUNT_DELETED", "PayoutAccount", accountId, Map.of());
    }

    /** The account a new redemption should be paid into, if any. */
    @Transactional(readOnly = true)
    public PayoutAccount requireVerifiedAccount(UUID userId) {
        List<PayoutAccount> verified = repository.findVerified(userId);
        if (verified.isEmpty()) {
            throw new ApiException(ErrorCode.PAYOUT_ACCOUNT_NOT_VERIFIED,
                    "Add a UPI ID or bank account and wait for it to be verified "
                            + "before claiming cashback.");
        }
        return verified.get(0);
    }

    @Transactional(readOnly = true)
    public boolean hasVerifiedAccount(UUID userId) {
        return !repository.findVerified(userId).isEmpty();
    }

    // --- admin review ----------------------------------------------------

    @Transactional(readOnly = true)
    public Page<UserDtos.AdminPayoutAccount> listForAdmin(PayoutStatus status, Pageable pageable) {
        return repository.findByStatus(status, pageable)
                .map(account -> new UserDtos.AdminPayoutAccount(
                        UserMapper.toDto(account),
                        account.getUser().getId(),
                        account.getUser().getEmail(),
                        account.getUser().getFullName()));
    }

    @Transactional
    public UserDtos.PayoutAccount verify(UUID adminId, UUID accountId) {
        PayoutAccount account = repository.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Payout account"));

        if (account.getStatus() == PayoutStatus.VERIFIED) {
            return UserMapper.toDto(account);
        }

        // Re-check at approval time: another user may have been verified on this
        // VPA while this request sat in the queue.
        if (account.getType() == PayoutType.UPI
                && repository.upiClaimedByAnotherUser(account.getUpiId(), account.getUser().getId())) {
            throw new ApiException(ErrorCode.PAYOUT_ACCOUNT_DUPLICATE,
                    "This UPI ID is already verified on another account.");
        }

        account.setStatus(PayoutStatus.VERIFIED);
        account.setVerifiedBy(adminId);
        account.setVerifiedAt(Instant.now());
        account.setRejectionReason(null);
        PayoutAccount saved = repository.save(account);

        User user = account.getUser();
        notificationService.notifyUser(user, NotificationType.PAYOUT_ACCOUNT_VERIFIED,
                "Payout account verified",
                saved.displayLabel() + " is verified. You can now claim cashback.",
                Map.of("payoutAccountId", saved.getId().toString()));
        safeEmail(() -> mailService.sendPayoutAccountVerified(user.getEmail(), saved.displayLabel()));

        auditService.record(adminId, "PAYOUT_ACCOUNT_VERIFIED", "PayoutAccount", accountId,
                Map.of("userId", user.getId().toString()));

        return UserMapper.toDto(saved);
    }

    @Transactional
    public UserDtos.PayoutAccount reject(UUID adminId, UUID accountId, String reason) {
        PayoutAccount account = repository.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("Payout account"));

        account.setStatus(PayoutStatus.REJECTED);
        account.setRejectionReason(reason);
        account.setVerifiedBy(adminId);
        account.setVerifiedAt(Instant.now());
        // A rejected account must not remain the default payout target.
        account.setPrimary(false);
        PayoutAccount saved = repository.save(account);

        User user = account.getUser();
        notificationService.notifyUser(user, NotificationType.PAYOUT_ACCOUNT_REJECTED,
                "Payout account needs attention",
                saved.displayLabel() + " could not be verified: " + reason,
                Map.of("payoutAccountId", saved.getId().toString()));
        safeEmail(() -> mailService.sendPayoutAccountRejected(user.getEmail(), saved.displayLabel(), reason));

        auditService.record(adminId, "PAYOUT_ACCOUNT_REJECTED", "PayoutAccount", accountId,
                Map.of("userId", user.getId().toString(), "reason", reason));

        return UserMapper.toDto(saved);
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** A bounced notification email must not roll back the admin's decision. */
    private void safeEmail(Runnable send) {
        try {
            send.run();
        } catch (Exception e) {
            log.warn("Notification email failed", e);
        }
    }
}