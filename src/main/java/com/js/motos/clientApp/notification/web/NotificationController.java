package com.js.motos.clientApp.notification.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.js.motos.clientApp.common.PageResponse;
import com.js.motos.clientApp.notification.NotificationService;
import com.js.motos.clientApp.notification.domain.Notification;
import com.js.motos.clientApp.notification.domain.NotificationType;
import com.js.motos.clientApp.security.AuthPrincipal;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notification feed")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "My notifications")
    public PageResponse<NotificationView> list(
            AuthPrincipal principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return PageResponse.from(
                notificationService.forUser(principal.userId(), unreadOnly, pageable),
                NotificationView::from);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many unread notifications I have")
    public Map<String, Long> unreadCount(AuthPrincipal principal) {
        return Map.of("count", notificationService.unreadCountForUser(principal.userId()));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "Mark one notification read")
    public ResponseEntity<Void> markRead(AuthPrincipal principal,
                                         @PathVariable UUID notificationId) {
        notificationService.markRead(notificationId, principal.userId(), principal.isAdmin());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications read")
    public ResponseEntity<Void> markAllRead(AuthPrincipal principal) {
        notificationService.markAllReadForUser(principal.userId());
        return ResponseEntity.noContent().build();
    }

    /** Wire format for a notification row. */
    public record NotificationView(
            UUID id,
            NotificationType type,
            String title,
            String body,
            String data,
            boolean read,
            Instant createdAt) {

        public static NotificationView from(Notification notification) {
            return new NotificationView(
                    notification.getId(),
                    notification.getType(),
                    notification.getTitle(),
                    notification.getBody(),
                    notification.getData(),
                    notification.isRead(),
                    notification.getCreatedAt());
        }
    }
}