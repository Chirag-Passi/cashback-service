package com.js.motos.clientApp.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.Setter;

/**
 * Append-only trail of money-affecting and privilege-affecting actions.
 * Nothing in the application ever updates or deletes these rows.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private String details;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}