package com.js.motos.clientApp.audit;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes the append-only audit trail for money- and privilege-affecting actions.
 *
 * <p>Entries are deliberately written <em>after</em> the calling transaction
 * commits. Two reasons:
 *
 * <ul>
 *   <li>{@code audit_logs.actor_user_id} is a foreign key into {@code users}. An
 *       audit row for a just-registered user, written from a separate
 *       transaction, would reference a row that has not committed yet and blow
 *       up on the FK.</li>
 *   <li>Audit noise must never roll back real work, and an entry should not
 *       survive an operation that was itself rolled back.</li>
 * </ul>
 *
 * <p>The trade-off is that a crash between commit and the audit write loses that
 * one entry. For an operational trail that is the right side to fail on; if this
 * ever needs to be tamper-evident bookkeeping, write it inside the business
 * transaction and drop the FK instead.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditService self;

    public AuditService(AuditLogRepository repository,
                        ObjectMapper objectMapper,
                        @Lazy AuditService self) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        // Self-injection so the @Transactional annotation below is honoured when
        // the write is invoked from an after-commit callback.
        this.self = self;
    }

    public void record(UUID actorUserId, String action, String entityType, Object entityId,
                       Map<String, ?> details, String ip) {

        String detailsJson = serialize(details);
        String entity = entityId == null ? null : entityId.toString();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeWrite(actorUserId, action, entityType, entity, detailsJson, ip);
                }
            });
        } else {
            safeWrite(actorUserId, action, entityType, entity, detailsJson, ip);
        }
    }

    public void record(UUID actorUserId, String action, String entityType, Object entityId,
                       Map<String, ?> details) {
        record(actorUserId, action, entityType, entityId, details, null);
    }

    private void safeWrite(UUID actorUserId, String action, String entityType,
                           String entityId, String detailsJson, String ip) {
        try {
            self.write(actorUserId, action, entityType, entityId, detailsJson, ip);
        } catch (Exception e) {
            // Never propagate: the business operation has already committed.
            log.error("Failed to write audit entry action={} entity={}:{}",
                    action, entityType, entityId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UUID actorUserId, String action, String entityType,
                      String entityId, String detailsJson, String ip) {
        AuditLog entry = new AuditLog();
        entry.setActorUserId(actorUserId);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetails(detailsJson);
        entry.setIp(ip);
        repository.save(entry);
    }

    private String serialize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn("Could not serialize audit details for action {}", details, e);
            return null;
        }
    }
}