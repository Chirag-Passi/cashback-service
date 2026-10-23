package com.js.motos.clientApp.admin.web;

import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.js.motos.clientApp.common.PageResponse;
import com.js.motos.clientApp.redemption.domain.RedemptionStatus;
import com.js.motos.clientApp.redemption.service.RedemptionService;
import com.js.motos.clientApp.redemption.web.dto.RedemptionDtos;
import com.js.motos.clientApp.security.AuthPrincipal;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin — Cashback", description = "Reviewing claims and recording manual UPI payouts")
public class AdminRedemptionController {

    private final RedemptionService redemptionService;

    public AdminRedemptionController(RedemptionService redemptionService) {
        this.redemptionService = redemptionService;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Headline counts for the admin home screen")
    public RedemptionDtos.Dashboard dashboard() {
        return redemptionService.dashboard();
    }

    @GetMapping("/redemptions")
    @Operation(summary = "List claims",
            description = "Filter by status; search matches reference number, user email or UPI ID.")
    public PageResponse<RedemptionDtos.AdminItem> list(
            @RequestParam(required = false) RedemptionStatus status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        String normalizedQuery = (query == null || query.isBlank()) ? null : query.trim();
        return PageResponse.from(
                redemptionService.listForAdmin(normalizedQuery, status, pageable), item -> item);
    }

    @GetMapping("/redemptions/{redemptionId}")
    @Operation(summary = "Full claim detail, including the payable UPI ID")
    public RedemptionDtos.AdminItem get(@PathVariable UUID redemptionId) {
        return redemptionService.getForAdmin(redemptionId);
    }

    @PostMapping("/redemptions/{redemptionId}/approve")
    @Operation(summary = "Approve a claim",
            description = "Signals intent to pay. The money still moves manually; "
                    + "call complete once it has actually been sent.")
    public RedemptionDtos.AdminItem approve(AuthPrincipal principal,
                                            @PathVariable UUID redemptionId,
                                            @RequestBody(required = false) RedemptionDtos.Approve request) {
        String notes = request == null ? null : request.notes();
        return redemptionService.approve(principal.userId(), redemptionId, notes);
    }

    @PostMapping(value = "/redemptions/{redemptionId}/complete",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Mark a claim paid and upload the receipt",
            description = "Multipart: 'receipt' is the payment screenshot (required), "
                    + "'utrReference' is the UPI/bank transaction reference. "
                    + "Notifies the user by push feed and email.")
    public RedemptionDtos.AdminItem complete(
            AuthPrincipal principal,
            @PathVariable UUID redemptionId,
            // Optional at the binding layer so a missing file produces the
            // RECEIPT_REQUIRED error rather than a raw 500 from Spring.
            @RequestPart(value = "receipt", required = false) MultipartFile receipt,
            @RequestParam(value = "utrReference", required = false) String utrReference,
            @RequestParam(value = "notes", required = false) String notes) {

        return redemptionService.complete(principal.userId(), redemptionId,
                utrReference, notes, receipt);
    }

    @PostMapping("/redemptions/{redemptionId}/reject")
    @Operation(summary = "Decline a claim",
            description = "Set releaseCode true to return the sticker to circulation so the "
                    + "user can retry; leave it false for suspected fraud.")
    public RedemptionDtos.AdminItem reject(AuthPrincipal principal,
                                           @PathVariable UUID redemptionId,
                                           @Valid @RequestBody RedemptionDtos.Reject request) {
        boolean release = Boolean.TRUE.equals(request.releaseCode());
        return redemptionService.reject(principal.userId(), redemptionId, request.reason(), release);
    }
}