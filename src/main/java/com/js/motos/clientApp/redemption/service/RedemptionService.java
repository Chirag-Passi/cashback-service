package com.js.motos.clientApp.redemption.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.js.motos.clientApp.audit.AuditService;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.mail.MailService;
import com.js.motos.clientApp.notification.NotificationService;
import com.js.motos.clientApp.notification.domain.NotificationType;
import com.js.motos.clientApp.qr.domain.QrCode;
import com.js.motos.clientApp.qr.domain.QrCodeStatus;
import com.js.motos.clientApp.qr.repo.QrCodeRepository;
import com.js.motos.clientApp.qr.service.QrCodeService;
import com.js.motos.clientApp.redemption.domain.Redemption;
import com.js.motos.clientApp.redemption.domain.RedemptionStatus;
import com.js.motos.clientApp.redemption.repo.RedemptionRepository;
import com.js.motos.clientApp.redemption.web.dto.RedemptionDtos;
import com.js.motos.clientApp.storage.FileStorage;
import com.js.motos.clientApp.user.domain.PayoutAccount;
import com.js.motos.clientApp.user.domain.PayoutStatus;
import com.js.motos.clientApp.user.domain.PayoutType;
import com.js.motos.clientApp.user.domain.User;
import com.js.motos.clientApp.user.repo.PayoutAccountRepository;
import com.js.motos.clientApp.user.service.PayoutAccountService;
import com.js.motos.clientApp.user.service.UserService;

/**
 * The cashback claim lifecycle: scan, admin review, and manual payout.
 *
 * <p>Payment itself happens outside this system — an admin sends the money by
 * UPI, then records the UTR and uploads the receipt here. Nothing in this class
 * moves money; it records who is owed what, and proves what was paid.
 */
@Service
public class RedemptionService {

    private static final Logger log = LoggerFactory.getLogger(RedemptionService.class);

    private final RedemptionRepository repository;
    private final QrCodeRepository qrCodeRepository;
    private final PayoutAccountRepository payoutAccountRepository;
    private final PayoutAccountService payoutAccountService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final FileStorage fileStorage;
    private final AuditService auditService;

    public RedemptionService(RedemptionRepository repository,
                             QrCodeRepository qrCodeRepository,
                             PayoutAccountRepository payoutAccountRepository,
                             PayoutAccountService payoutAccountService,
                             UserService userService,
                             NotificationService notificationService,
                             MailService mailService,
                             FileStorage fileStorage,
                             AuditService auditService) {
        this.repository = repository;
        this.qrCodeRepository = qrCodeRepository;
        this.payoutAccountRepository = payoutAccountRepository;
        this.payoutAccountService = payoutAccountService;
        this.userService = userService;
        this.notificationService = notificationService;
        this.mailService = mailService;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
    }

    // =====================================================================
    // User: claim a scanned code
    // =====================================================================

    /**
     * Claims a scanned QR code for the calling user.
     *
     * <p>The code row is locked FOR UPDATE first, so two devices scanning the same
     * sticker simultaneously serialise here and only one claim is created. The
     * UNIQUE constraint on {@code redemptions.qr_code_id} is the backstop.
     */
    @Transactional
    public RedemptionDtos.Item claim(UUID userId, String rawScan) {
        User user = userService.load(userId);
        PayoutAccount payoutAccount = payoutAccountService.requireVerifiedAccount(userId);

        String code = QrCodeService.extractCode(rawScan);
        QrCode qrCode = qrCodeRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> new ApiException(ErrorCode.QR_NOT_FOUND,
                        "This QR code is not recognised."));

        assertClaimable(qrCode);

        qrCode.setStatus(QrCodeStatus.REDEEMED);
        qrCode.setRedeemedBy(userId);
        qrCode.setRedeemedAt(Instant.now());
        qrCodeRepository.save(qrCode);

        Redemption redemption = new Redemption();
        redemption.setReferenceNo(nextReferenceNo());
        redemption.setUser(user);
        redemption.setQrCode(qrCode);
        redemption.setAmount(qrCode.getAmount());
        redemption.setStatus(RedemptionStatus.PENDING);
        redemption.setRequestedAt(Instant.now());
        redemption.snapshotPayout(payoutAccount);

        Redemption saved = repository.save(redemption);

        notificationService.notifyAdmins(
                NotificationType.REDEMPTION_REQUESTED,
                "New cashback claim " + saved.getReferenceNo(),
                user.getEmail() + " claimed " + money(saved.getAmount())
                        + " to " + payoutAccount.displayLabel() + ".",
                Map.of("redemptionId", saved.getId().toString(),
                        "referenceNo", saved.getReferenceNo(),
                        "amount", saved.getAmount().toPlainString()));

        auditService.record(userId, "REDEMPTION_CLAIMED", "Redemption", saved.getId(),
                Map.of("qrCode", code, "amount", saved.getAmount().toPlainString()));

        log.info("User {} claimed {} via code {} ({})",
                userId, saved.getAmount(), code, saved.getReferenceNo());

        return toItem(saved);
    }

    private void assertClaimable(QrCode qrCode) {
        switch (qrCode.getStatus()) {
            case REDEEMED -> throw new ApiException(ErrorCode.QR_ALREADY_REDEEMED,
                    "This code has already been claimed.");
            case VOID -> throw new ApiException(ErrorCode.QR_VOID,
                    "This code is no longer valid.");
            case EXPIRED -> throw new ApiException(ErrorCode.QR_EXPIRED,
                    "This code has expired.");
            case ACTIVE -> {
                // Expiry may have passed without the sweep having run yet.
                if (qrCode.isExpired()) {
                    qrCode.setStatus(QrCodeStatus.EXPIRED);
                    throw new ApiException(ErrorCode.QR_EXPIRED, "This code has expired.");
                }
            }
        }

        if (repository.existsByQrCodeId(qrCode.getId())) {
            throw new ApiException(ErrorCode.QR_ALREADY_REDEEMED,
                    "This code has already been claimed.");
        }
    }

    private String nextReferenceNo() {
        return String.format("RDM-%06d", repository.nextReferenceSequence());
    }

    // =====================================================================
    // User: history
    // =====================================================================

    @Transactional(readOnly = true)
    public Page<RedemptionDtos.Item> listForUser(UUID userId, RedemptionStatus status, Pageable pageable) {
        return repository.findForUser(userId, status, pageable).map(this::toItem);
    }

    @Transactional(readOnly = true)
    public RedemptionDtos.Item getForUser(UUID userId, UUID redemptionId) {
        return repository.findByIdAndUserId(redemptionId, userId)
                .map(this::toItem)
                .orElseThrow(() -> ApiException.notFound("Redemption"));
    }

    // =====================================================================
    // Admin: review and payout
    // =====================================================================

    @Transactional(readOnly = true)
    public Page<RedemptionDtos.AdminItem> listForAdmin(String query, RedemptionStatus status,
                                                       Pageable pageable) {
        return repository.searchForAdmin(query, status, pageable).map(this::toAdminItem);
    }

    @Transactional(readOnly = true)
    public RedemptionDtos.AdminItem getForAdmin(UUID redemptionId) {
        return repository.findById(redemptionId)
                .map(this::toAdminItem)
                .orElseThrow(() -> ApiException.notFound("Redemption"));
    }

    @Transactional
    public RedemptionDtos.AdminItem approve(UUID adminId, UUID redemptionId, String notes) {
        Redemption redemption = load(redemptionId);
        requireStatus(redemption, RedemptionStatus.PENDING, "approved");

        redemption.setStatus(RedemptionStatus.APPROVED);
        redemption.setApprovedAt(Instant.now());
        redemption.setApprovedBy(adminId);
        if (notes != null && !notes.isBlank()) {
            redemption.setAdminNotes(notes);
        }
        Redemption saved = repository.save(redemption);

        notificationService.notifyUser(saved.getUser(), NotificationType.REDEMPTION_APPROVED,
                "Cashback approved",
                money(saved.getAmount()) + " approved. Payment is on its way.",
                Map.of("redemptionId", saved.getId().toString(),
                        "referenceNo", saved.getReferenceNo()));

        auditService.record(adminId, "REDEMPTION_APPROVED", "Redemption", redemptionId,
                Map.of("amount", saved.getAmount().toPlainString()));

        return toAdminItem(saved);
    }

    /**
     * Records a completed manual payout: UTR plus a receipt screenshot.
     *
     * <p>The upload happens before the status flips, so a storage failure leaves
     * the claim unpaid rather than marked paid with no proof.
     */
    @Transactional
    public RedemptionDtos.AdminItem complete(UUID adminId, UUID redemptionId,
                                             String utrReference, String notes,
                                             MultipartFile receipt) {
        Redemption redemption = load(redemptionId);

        if (redemption.getStatus() != RedemptionStatus.PENDING
                && redemption.getStatus() != RedemptionStatus.APPROVED) {
            throw new ApiException(ErrorCode.REDEMPTION_INVALID_STATE,
                    "Only a pending or approved claim can be marked paid. This one is "
                            + redemption.getStatus() + ".");
        }
        if (receipt == null || receipt.isEmpty()) {
            throw new ApiException(ErrorCode.RECEIPT_REQUIRED,
                    "Upload the payment receipt screenshot to mark this as paid.");
        }

        FileStorage.StoredFile stored = fileStorage.upload(receipt, "receipts");

        redemption.setStatus(RedemptionStatus.PAID);
        redemption.setPaidAt(Instant.now());
        redemption.setPaidBy(adminId);
        redemption.setUtrReference(utrReference == null ? null : utrReference.trim());
        redemption.setReceiptUrl(stored.url());
        redemption.setReceiptPublicId(stored.publicId());
        if (notes != null && !notes.isBlank()) {
            redemption.setAdminNotes(notes);
        }
        if (redemption.getApprovedAt() == null) {
            // Paid straight from PENDING; record the implicit approval.
            redemption.setApprovedAt(Instant.now());
            redemption.setApprovedBy(adminId);
        }

        Redemption saved = repository.save(redemption);
        User user = saved.getUser();
        String target = payoutTarget(saved);

        notificationService.notifyUser(user, NotificationType.REDEMPTION_PAID,
                "Cashback paid",
                money(saved.getAmount()) + " sent to " + target + ".",
                Map.of("redemptionId", saved.getId().toString(),
                        "referenceNo", saved.getReferenceNo(),
                        "receiptUrl", stored.url()));

        safeEmail(() -> mailService.sendRedemptionPaid(
                user.getEmail(), saved.getReferenceNo(), saved.getAmount(), target));

        auditService.record(adminId, "REDEMPTION_PAID", "Redemption", redemptionId,
                Map.of("amount", saved.getAmount().toPlainString(),
                        "utr", String.valueOf(saved.getUtrReference())));

        log.info("Admin {} marked {} paid ({})", adminId, saved.getReferenceNo(), saved.getAmount());
        return toAdminItem(saved);
    }

    @Transactional
    public RedemptionDtos.AdminItem reject(UUID adminId, UUID redemptionId,
                                           String reason, boolean releaseCode) {
        Redemption redemption = load(redemptionId);

        if (redemption.isTerminal()) {
            throw new ApiException(ErrorCode.REDEMPTION_INVALID_STATE,
                    "This claim is already " + redemption.getStatus() + " and cannot be rejected.");
        }

        redemption.setStatus(RedemptionStatus.REJECTED);
        redemption.setRejectionReason(reason);
        redemption.setApprovedBy(adminId);

        if (releaseCode) {
            // Put the sticker back in circulation so the user can retry after
            // fixing whatever was wrong.
            QrCode qrCode = redemption.getQrCode();
            qrCode.setStatus(qrCode.isExpired() ? QrCodeStatus.EXPIRED : QrCodeStatus.ACTIVE);
            qrCode.setRedeemedBy(null);
            qrCode.setRedeemedAt(null);
            qrCodeRepository.save(qrCode);
        }

        Redemption saved = repository.save(redemption);
        User user = saved.getUser();

        notificationService.notifyUser(user, NotificationType.REDEMPTION_REJECTED,
                "Cashback claim declined",
                "Claim " + saved.getReferenceNo() + " was declined: " + reason,
                Map.of("redemptionId", saved.getId().toString(),
                        "referenceNo", saved.getReferenceNo()));

        safeEmail(() -> mailService.sendRedemptionRejected(
                user.getEmail(), saved.getReferenceNo(), reason));

        auditService.record(adminId, "REDEMPTION_REJECTED", "Redemption", redemptionId,
                Map.of("reason", reason, "codeReleased", releaseCode));

        return toAdminItem(saved);
    }

    @Transactional(readOnly = true)
    public RedemptionDtos.Dashboard dashboard() {
        return new RedemptionDtos.Dashboard(
                repository.countByStatus(RedemptionStatus.PENDING),
                repository.countByStatus(RedemptionStatus.APPROVED),
                repository.countByStatus(RedemptionStatus.PAID),
                repository.countByStatus(RedemptionStatus.REJECTED),
                repository.sumAmountByStatus(RedemptionStatus.PENDING),
                repository.sumAmountByStatus(RedemptionStatus.PAID),
                payoutAccountRepository.countByStatus(PayoutStatus.PENDING),
                qrCodeRepository.countByStatus(QrCodeStatus.ACTIVE),
                qrCodeRepository.countByStatus(QrCodeStatus.REDEEMED));
    }

    // =====================================================================
    // Mapping
    // =====================================================================

    private Redemption load(UUID redemptionId) {
        return repository.findById(redemptionId).orElseThrow(() -> ApiException.notFound("Redemption"));
    }

    private void requireStatus(Redemption redemption, RedemptionStatus expected, String verb) {
        if (redemption.getStatus() != expected) {
            throw new ApiException(ErrorCode.REDEMPTION_INVALID_STATE,
                    "Only a " + expected + " claim can be " + verb + ". This one is "
                            + redemption.getStatus() + ".");
        }
    }

    private RedemptionDtos.Item toItem(Redemption redemption) {
        return new RedemptionDtos.Item(
                redemption.getId(),
                redemption.getReferenceNo(),
                redemption.getAmount(),
                redemption.getStatus(),
                redemption.getPayoutTypeSnapshot(),
                payoutTarget(redemption),
                redemption.getRequestedAt(),
                redemption.getApprovedAt(),
                redemption.getPaidAt(),
                redemption.getUtrReference(),
                redemption.getReceiptUrl(),
                redemption.getRejectionReason());
    }

    private RedemptionDtos.AdminItem toAdminItem(Redemption redemption) {
        User user = redemption.getUser();
        return new RedemptionDtos.AdminItem(
                toItem(redemption),
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                redemption.getUpiIdSnapshot(),
                redemption.getHolderNameSnapshot(),
                redemption.getIfscSnapshot(),
                redemption.getBankLast4Snapshot(),
                redemption.getAdminNotes(),
                redemption.getQrCode().getCode());
    }

    /** Display-safe payout target; bank accounts stay masked. */
    private String payoutTarget(Redemption redemption) {
        if (redemption.getPayoutTypeSnapshot() == PayoutType.UPI) {
            return redemption.getUpiIdSnapshot();
        }
        return "account ****" + redemption.getBankLast4Snapshot();
    }

    private static String money(BigDecimal amount) {
        return "₹" + amount.stripTrailingZeros().toPlainString();
    }

    private void safeEmail(Runnable send) {
        try {
            send.run();
        } catch (Exception e) {
            log.warn("Notification email failed", e);
        }
    }
}