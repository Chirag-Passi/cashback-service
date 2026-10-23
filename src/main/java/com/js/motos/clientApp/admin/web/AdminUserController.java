package com.js.motos.clientApp.admin.web;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.js.motos.clientApp.admin.service.AdminUserService;
import com.js.motos.clientApp.common.PageResponse;
import com.js.motos.clientApp.notification.NotificationService;
import com.js.motos.clientApp.notification.web.NotificationController.NotificationView;
import com.js.motos.clientApp.security.AuthPrincipal;
import com.js.motos.clientApp.user.domain.PayoutStatus;
import com.js.motos.clientApp.user.domain.UserStatus;
import com.js.motos.clientApp.user.service.PayoutAccountService;
import com.js.motos.clientApp.user.web.dto.UserDtos;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin — Users", description = "User management and payout-account verification")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final PayoutAccountService payoutAccountService;
    private final NotificationService notificationService;

    public AdminUserController(AdminUserService adminUserService,
                               PayoutAccountService payoutAccountService,
                               NotificationService notificationService) {
        this.adminUserService = adminUserService;
        this.payoutAccountService = payoutAccountService;
        this.notificationService = notificationService;
    }

    // --- users ------------------------------------------------------------

    @GetMapping("/users")
    @Operation(summary = "Search users by email, name or phone")
    public PageResponse<AdminUserService.AdminUserView> listUsers(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        String normalized = (query == null || query.isBlank()) ? null : query.trim();
        return PageResponse.from(adminUserService.search(normalized, status, pageable), view -> view);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Full detail for one user")
    public AdminUserService.AdminUserDetail getUser(@PathVariable UUID userId) {
        return adminUserService.detail(userId);
    }

    @PostMapping("/users/{userId}/block")
    @Operation(summary = "Block a user",
            description = "Revokes every session immediately; the user cannot log back in.")
    public AdminUserService.AdminUserView block(AuthPrincipal principal,
                                                @PathVariable UUID userId,
                                                @RequestBody(required = false) BlockRequest request) {
        String reason = request == null ? null : request.reason();
        return adminUserService.block(principal.userId(), userId, reason);
    }

    @PostMapping("/users/{userId}/unblock")
    @Operation(summary = "Restore a blocked user")
    public AdminUserService.AdminUserView unblock(AuthPrincipal principal,
                                                  @PathVariable UUID userId) {
        return adminUserService.unblock(principal.userId(), userId);
    }

    // --- payout account verification --------------------------------------

    @GetMapping("/payout-accounts")
    @Operation(summary = "Payout accounts awaiting review",
            description = "Defaults to PENDING — this is the verification queue.")
    public PageResponse<UserDtos.AdminPayoutAccount> listPayoutAccounts(
            @RequestParam(defaultValue = "PENDING") PayoutStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return PageResponse.from(payoutAccountService.listForAdmin(status, pageable), item -> item);
    }

    @PostMapping("/payout-accounts/{accountId}/verify")
    @Operation(summary = "Verify a UPI ID or bank account",
            description = "Until this happens the user cannot claim any cashback.")
    public UserDtos.PayoutAccount verifyAccount(AuthPrincipal principal,
                                                @PathVariable UUID accountId) {
        return payoutAccountService.verify(principal.userId(), accountId);
    }

    @PostMapping("/payout-accounts/{accountId}/reject")
    @Operation(summary = "Reject a payout account with a reason")
    public UserDtos.PayoutAccount rejectAccount(AuthPrincipal principal,
                                                @PathVariable UUID accountId,
                                                @Valid @RequestBody UserDtos.RejectPayoutAccount request) {
        return payoutAccountService.reject(principal.userId(), accountId, request.reason());
    }

    // --- admin notification feed -------------------------------------------

    @GetMapping("/notifications")
    @Operation(summary = "Admin notification feed",
            description = "New claims and payout accounts awaiting action. Shared by all admins.")
    public PageResponse<NotificationView> notifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return PageResponse.from(
                notificationService.forAdmins(unreadOnly, pageable), NotificationView::from);
    }

    @GetMapping("/notifications/unread-count")
    @Operation(summary = "Unread count for the admin feed")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.unreadCountForAdmins());
    }

    @PostMapping("/notifications/read-all")
    @Operation(summary = "Mark the whole admin feed read")
    public Map<String, String> markAllRead() {
        notificationService.markAllReadForAdmins();
        return Map.of("status", "ok");
    }

    public record BlockRequest(String reason) {
    }
}