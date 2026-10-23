package com.js.motos.clientApp.notification.repo;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.js.motos.clientApp.notification.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            SELECT n FROM Notification n
            WHERE n.audience = 'USER' AND n.recipient.id = :userId
              AND (:unreadOnly = false OR n.readAt IS NULL)
            ORDER BY n.createdAt DESC
            """)
    Page<Notification> findForUser(@Param("userId") UUID userId,
                                   @Param("unreadOnly") boolean unreadOnly,
                                   Pageable pageable);

    @Query("""
            SELECT n FROM Notification n
            WHERE n.audience = 'ADMIN'
              AND (:unreadOnly = false OR n.readAt IS NULL)
            ORDER BY n.createdAt DESC
            """)
    Page<Notification> findForAdmins(@Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    @Query("""
            SELECT count(n) FROM Notification n
            WHERE n.audience = 'USER' AND n.recipient.id = :userId AND n.readAt IS NULL
            """)
    long countUnreadForUser(@Param("userId") UUID userId);

    @Query("SELECT count(n) FROM Notification n WHERE n.audience = 'ADMIN' AND n.readAt IS NULL")
    long countUnreadForAdmins();

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Notification n SET n.readAt = CURRENT_TIMESTAMP
            WHERE n.audience = 'USER' AND n.recipient.id = :userId AND n.readAt IS NULL
            """)
    int markAllReadForUser(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = CURRENT_TIMESTAMP WHERE n.audience = 'ADMIN' AND n.readAt IS NULL")
    int markAllReadForAdmins();
}