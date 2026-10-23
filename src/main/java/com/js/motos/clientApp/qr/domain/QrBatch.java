package com.js.motos.clientApp.qr.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * A print run: "500 codes worth ₹100 each, for the March campaign". Every QR
 * code belongs to a batch so admins can track and void whole print runs.
 */
@Entity
@Table(name = "qr_batches")
@Getter
@Setter
public class QrBatch {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "label", nullable = false, length = 150)
    private String label;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}