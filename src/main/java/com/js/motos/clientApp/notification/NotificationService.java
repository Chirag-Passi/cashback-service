package com.js.motos.clientApp.notification;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.notification.domain.Notification;
import com.js.motos.clientApp.notification.domain.NotificationAudience;
import com.js.motos.clientApp.notification.domain.NotificationType;
import com.js.motos.clientApp.notification.repo.NotificationRepository;
import com.js.motos.clientApp.user.domain.User;

/**
 * In-app notification feed.
 *
 * <p>Admin-audience rows are written once and read by every admin, rather than
 * fanned out per admin account — the admin console polls this feed for pending
 * and completed redemptions.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void notifyAdmins(NotificationType type, String title, String body, Map<String, ?> data) {
        Notification notification = new Notification();
        notification.setAudience(NotificationAudience.ADMIN);
        notification.setType(type);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setData(serialize(data));
        repository.save(notification);
    }

    @Transactional
    public void notifyUser(User user, NotificationType type, String title, String body,
                           Map<String, ?> data) {
        Notification notification = new Notification();
        notification.setAudience(NotificationAudience.USER);
        notification.setRecipient(user);
        notification.setType(type);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setData(serialize(data));
        repository.save(notification);
    }

    @Transactional(readOnly = true)
    public Page<Notification> forUser(UUID userId, boolean unreadOnly, Pageable pageable) {
        return repository.findForUser(userId, unreadOnly, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Notification> forAdmins(boolean unreadOnly, Pageable pageable) {
        return repository.findForAdmins(unreadOnly, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCountForUser(UUID userId) {
        return repository.countUnreadForUser(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCountForAdmins() {
        return repository.countUnreadForAdmins();
    }

    @Transactional
    public void markRead(UUID notificationId, UUID userId, boolean isAdmin) {
        Notification notification = repository.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("Notification"));

        boolean ownedByCaller = notification.getAudience() == NotificationAudience.ADMIN
                ? isAdmin
                : notification.getRecipient() != null
                        && notification.getRecipient().getId().equals(userId);

        if (!ownedByCaller) {
            // Same response as a genuine miss, so ids cannot be probed.
            throw ApiException.notFound("Notification");
        }
        if (!notification.isRead()) {
            notification.setReadAt(java.time.Instant.now());
        }
    }

    @Transactional
    public void markAllReadForUser(UUID userId) {
        repository.markAllReadForUser(userId);
    }

    @Transactional
    public void markAllReadForAdmins() {
        repository.markAllReadForAdmins();
    }

    private String serialize(Map<String, ?> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Could not serialize notification payload", e);
            return null;
        }
    }
}