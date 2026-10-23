package com.js.motos.clientApp.notification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.js.motos.clientApp.user.domain.User;

import lombok.Getter;
import lombok.Setter;

/**
 * In-app notification feed entry. Admin-audience rows have a null recipient and
 * are readable by every admin — that's the "admin gets notified of pending and
 * completed transactions" surface.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 10)
    private NotificationAudience audience;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id")
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "body")
    private String body;

    /** Free-form payload for client-side deep links, e.g. the redemption id. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data")
    private String data;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isRead() {
        return readAt != null;
    }
}