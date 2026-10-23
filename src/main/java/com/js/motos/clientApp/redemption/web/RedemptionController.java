package com.js.motos.clientApp.redemption.web;

import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.js.motos.clientApp.common.PageResponse;
import com.js.motos.clientApp.qr.service.QrCodeService;
import com.js.motos.clientApp.qr.web.dto.QrDtos;
import com.js.motos.clientApp.redemption.domain.RedemptionStatus;
import com.js.motos.clientApp.redemption.service.RedemptionService;
import com.js.motos.clientApp.redemption.web.dto.RedemptionDtos;
import com.js.motos.clientApp.security.AuthPrincipal;
import com.js.motos.clientApp.user.service.PayoutAccountService;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cashback", description = "Scanning a physical QR code and tracking the payout")
public class RedemptionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RedemptionService redemptionService;
    private final QrCodeService qrCodeService;
    private final PayoutAccountService payoutAccountService;

    public RedemptionController(RedemptionService redemptionService,
                                QrCodeService qrCodeService,
                                PayoutAccountService payoutAccountService) {
        this.redemptionService = redemptionService;
        this.qrCodeService = qrCodeService;
        this.payoutAccountService = payoutAccountService;
    }

    @GetMapping("/qr/preview")
    @Operation(summary = "Check a scanned code before claiming",
            description = "Non-destructive. Tells the app whether the code is claimable "
                    + "and how much it is worth, so it can show a confirmation screen.")
    public QrDtos.ScanPreview preview(AuthPrincipal principal,
                                      @RequestParam("scan") String scan) {
        boolean payoutReady = payoutAccountService.hasVerifiedAccount(principal.userId());
        return qrCodeService.preview(scan, payoutReady);
    }

    @PostMapping("/redemptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Claim a scanned QR code",
            description = "Requires a verified payout account. Creates a PENDING claim "
                    + "for an admin to pay out. A code can only ever be claimed once.")
    public RedemptionDtos.Item claim(AuthPrincipal principal,
                                     @Valid @RequestBody RedemptionDtos.Claim request) {
        return redemptionService.claim(principal.userId(), request.scan());
    }

    @GetMapping("/redemptions")
    @Operation(summary = "My cashback history")
    public PageResponse<RedemptionDtos.Item> myRedemptions(
            AuthPrincipal principal,
            @RequestParam(required = false) RedemptionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        var results = redemptionService.listForUser(principal.userId(), status, pageable);
        return PageResponse.from(results, item -> item);
    }

    @GetMapping("/redemptions/{redemptionId}")
    @Operation(summary = "Get one of my claims, including the payment receipt")
    public RedemptionDtos.Item getRedemption(AuthPrincipal principal,
                                             @PathVariable UUID redemptionId) {
        return redemptionService.getForUser(principal.userId(), redemptionId);
    }

    static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
