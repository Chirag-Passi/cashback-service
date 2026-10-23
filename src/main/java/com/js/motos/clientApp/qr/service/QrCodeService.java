package com.js.motos.clientApp.qr.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.js.motos.clientApp.audit.AuditService;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.Codes;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.config.AppProperties;
import com.js.motos.clientApp.qr.domain.QrBatch;
import com.js.motos.clientApp.qr.domain.QrCode;
import com.js.motos.clientApp.qr.domain.QrCodeStatus;
import com.js.motos.clientApp.qr.repo.QrBatchRepository;
import com.js.motos.clientApp.qr.repo.QrCodeRepository;
import com.js.motos.clientApp.qr.web.dto.QrDtos;

/**
 * Minting, listing and voiding the physical cashback codes.
 */
@Service
public class QrCodeService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeService.class);

    private final QrBatchRepository batchRepository;
    private final QrCodeRepository codeRepository;
    private final AuditService auditService;
    private final AppProperties properties;

    public QrCodeService(QrBatchRepository batchRepository,
                         QrCodeRepository codeRepository,
                         AuditService auditService,
                         AppProperties properties) {
        this.batchRepository = batchRepository;
        this.codeRepository = codeRepository;
        this.auditService = auditService;
        this.properties = properties;
    }

    // =====================================================================
    // Creation
    // =====================================================================

    @Transactional
    public QrDtos.BatchSummary createBatch(UUID adminId, QrDtos.CreateBatch request) {
        QrBatch batch = new QrBatch();
        batch.setLabel(request.label().trim());
        batch.setAmount(request.amount());
        batch.setQuantity(request.quantity());
        batch.setExpiresAt(request.expiresAt());
        batch.setNotes(request.notes());
        batch.setCreatedBy(adminId);
        QrBatch savedBatch = batchRepository.save(batch);

        List<QrCode> codes = new ArrayList<>(request.quantity());
        Set<String> seen = new HashSet<>(request.quantity() * 2);

        for (int i = 0; i < request.quantity(); i++) {
            QrCode code = new QrCode();
            code.setBatch(savedBatch);
            code.setCode(uniqueCode(seen));
            code.setAmount(request.amount());
            code.setExpiresAt(request.expiresAt());
            code.setStatus(QrCodeStatus.ACTIVE);
            codes.add(code);
        }
        codeRepository.saveAll(codes);

        log.info("Admin {} minted {} codes worth {} each (batch {})",
                adminId, request.quantity(), request.amount(), savedBatch.getId());

        auditService.record(adminId, "QR_BATCH_CREATED", "QrBatch", savedBatch.getId(),
                Map.of("quantity", request.quantity(),
                        "amount", request.amount().toPlainString(),
                        "label", savedBatch.getLabel()));

        return toSummary(savedBatch);
    }

    /**
     * Draws a code that collides with neither this batch nor the table. At ~78
     * bits of entropy a collision is vanishingly unlikely, but the DB has a UNIQUE
     * constraint and a duplicate would fail the whole batch insert.
     */
    private String uniqueCode(Set<String> seen) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = Codes.qrCode();
            if (seen.add(candidate) && !codeRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "Could not allocate a unique code. Please retry.");
    }

    // =====================================================================
    // Reads
    // =====================================================================

    @Transactional(readOnly = true)
    public Page<QrDtos.BatchSummary> listBatches(Pageable pageable) {
        return batchRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public QrDtos.BatchSummary getBatch(UUID batchId) {
        return toSummary(loadBatch(batchId));
    }

    @Transactional(readOnly = true)
    public Page<QrDtos.QrCodeItem> listCodes(UUID batchId, Pageable pageable) {
        return codeRepository.findAllByBatchId(batchId, pageable).map(this::toItem);
    }

    @Transactional(readOnly = true)
    public List<QrCode> allCodesInBatch(UUID batchId) {
        loadBatch(batchId);
        return codeRepository.findAllByBatchIdOrderByCreatedAtAsc(batchId);
    }

    @Transactional(readOnly = true)
    public QrDtos.QrCodeItem getByCode(String rawScan) {
        QrCode code = codeRepository.findByCode(extractCode(rawScan))
                .orElseThrow(() -> new ApiException(ErrorCode.QR_NOT_FOUND, "Unknown code."));
        return toItem(code);
    }

    /**
     * What the app shows straight after a scan. Returns a claimable/not-claimable
     * verdict rather than throwing, so the UI can explain the reason.
     */
    @Transactional(readOnly = true)
    public QrDtos.ScanPreview preview(String rawScan, boolean payoutReady) {
        String code = extractCode(rawScan);

        return codeRepository.findByCode(code)
                .map(qr -> {
                    if (qr.getStatus() == QrCodeStatus.REDEEMED) {
                        return new QrDtos.ScanPreview(false, qr.getAmount(), qr.getStatus(),
                                "This code has already been claimed.", payoutReady);
                    }
                    if (qr.getStatus() == QrCodeStatus.VOID) {
                        return new QrDtos.ScanPreview(false, qr.getAmount(), qr.getStatus(),
                                "This code is no longer valid.", payoutReady);
                    }
                    if (qr.isExpired() || qr.getStatus() == QrCodeStatus.EXPIRED) {
                        return new QrDtos.ScanPreview(false, qr.getAmount(), QrCodeStatus.EXPIRED,
                                "This code has expired.", payoutReady);
                    }
                    if (!payoutReady) {
                        return new QrDtos.ScanPreview(false, qr.getAmount(), qr.getStatus(),
                                "Add a UPI ID and get it verified before claiming.", false);
                    }
                    return new QrDtos.ScanPreview(true, qr.getAmount(), qr.getStatus(),
                            "Ready to claim.", true);
                })
                .orElse(new QrDtos.ScanPreview(false, null, null,
                        "This QR code is not recognised.", payoutReady));
    }

    // =====================================================================
    // Void
    // =====================================================================

    @Transactional
    public QrDtos.QrCodeItem voidCode(UUID adminId, UUID codeId, String reason) {
        QrCode code = codeRepository.findById(codeId)
                .orElseThrow(() -> new ApiException(ErrorCode.QR_NOT_FOUND, "Unknown code."));

        if (code.getStatus() == QrCodeStatus.REDEEMED) {
            throw new ApiException(ErrorCode.QR_ALREADY_REDEEMED,
                    "This code has already been claimed and cannot be voided.");
        }

        code.setStatus(QrCodeStatus.VOID);
        auditService.record(adminId, "QR_CODE_VOIDED", "QrCode", codeId, Map.of("reason", reason));
        return toItem(codeRepository.save(code));
    }

    /** Voids every unclaimed code in a batch — for a lost or misprinted run. */
    @Transactional
    public int voidBatch(UUID adminId, UUID batchId, String reason) {
        loadBatch(batchId);
        int affected = codeRepository.voidActiveInBatch(batchId);
        auditService.record(adminId, "QR_BATCH_VOIDED", "QrBatch", batchId,
                Map.of("reason", reason, "codesVoided", affected));
        return affected;
    }

    /** Hourly sweep so listings and counts reflect expiry without a read-time check. */
    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void expireStaleCodes() {
        int expired = codeRepository.markExpired(Instant.now());
        if (expired > 0) {
            log.info("Marked {} QR codes as expired", expired);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Accepts either the bare printed code or the full deep-link URL the QR image
     * encodes, so the Android scanner can pass through whatever it decoded.
     */
    public static String extractCode(String rawScan) {
        if (rawScan == null || rawScan.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No code was scanned.");
        }
        String value = rawScan.trim();

        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash < value.length() - 1) {
            value = value.substring(slash + 1);
        }
        int question = value.indexOf('?');
        if (question >= 0) {
            value = value.substring(0, question);
        }
        return value.toUpperCase();
    }

    /** The string encoded into the printed QR image. */
    public String payloadFor(QrCode code) {
        return properties.baseUrl() + "/r/" + code.getCode();
    }

    private QrBatch loadBatch(UUID batchId) {
        return batchRepository.findById(batchId).orElseThrow(() -> ApiException.notFound("Batch"));
    }

    private QrDtos.BatchSummary toSummary(QrBatch batch) {
        long active = codeRepository.countByBatchIdAndStatus(batch.getId(), QrCodeStatus.ACTIVE);
        long redeemed = codeRepository.countByBatchIdAndStatus(batch.getId(), QrCodeStatus.REDEEMED);
        long expired = codeRepository.countByBatchIdAndStatus(batch.getId(), QrCodeStatus.EXPIRED);
        long voided = codeRepository.countByBatchIdAndStatus(batch.getId(), QrCodeStatus.VOID);

        QrDtos.BatchStats stats = new QrDtos.BatchStats(
                active, redeemed, expired, voided,
                batch.getAmount().multiply(BigDecimal.valueOf(batch.getQuantity())),
                batch.getAmount().multiply(BigDecimal.valueOf(redeemed)));

        return new QrDtos.BatchSummary(
                batch.getId(), batch.getLabel(), batch.getAmount(), batch.getQuantity(),
                batch.getExpiresAt(), batch.getNotes(), batch.getCreatedAt(), stats);
    }

    private QrDtos.QrCodeItem toItem(QrCode code) {
        return new QrDtos.QrCodeItem(
                code.getId(), code.getCode(), code.getAmount(), code.getStatus(),
                code.getExpiresAt(), code.getRedeemedAt(), payloadFor(code));
    }
}