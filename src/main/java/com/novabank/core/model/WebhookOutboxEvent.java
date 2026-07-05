package com.novabank.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Transactional outbox record for a webhook notification.
 *
 * WHY THIS EXISTS (Critical Issue #1 from the code review): {@code TransactionService
 * .performTransfer()} and {@code AccountService.updateAccountStatus()} previously called
 * {@code WebhookService.notifyEvent()} — a synchronous, blocking HTTP call — from inside an
 * active {@code @Transactional} method. A slow or hung webhook target directly extended
 * database lock hold time on {@code Account} rows during a live funds transfer.
 *
 * The fix follows the transactional outbox pattern: instead of making the HTTP call inline,
 * the business method writes a row to this table — a fast, local, in-transaction DB insert with
 * no external I/O — as part of the *same* database transaction as the business change. This
 * gives an atomicity guarantee an application-event-only approach cannot: either both the
 * balance change and the outbox row commit together, or neither do (no risk of "transfer
 * succeeded but the outbox write was lost" on a mid-transaction crash). A separate
 * {@code WebhookDispatcher} scheduled process then delivers pending events asynchronously,
 * completely off the request thread, with retry and a dead-letter ceiling.
 */
@Entity
@Table(name = "webhook_outbox_events", indexes = {
        @Index(name = "idx_webhook_outbox_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class WebhookOutboxEvent extends BaseEntity {

    public enum Status { PENDING, SENT, FAILED }

    @Column(nullable = false, length = 100)
    private String eventType;

    // Deliberately NOT @Lob: on PostgreSQL, Hibernate 6 maps a @Lob String to Types.CLOB, which
    // the PostgreSQL dialect materializes as an `oid` large-object column, not `TEXT` — this
    // mismatched V2__webhook_outbox.sql's plain `TEXT` column and only surfaced against a real
    // PostgreSQL instance (FlywayMigrationPostgresIT), never against H2, which every other test
    // in this suite runs against. `columnDefinition` pins the real column type explicitly so
    // Hibernate's schema *validation* (ddl-auto=validate) agrees with what Flyway created.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column
    private Instant lastAttemptAt;

    @Column(length = 500)
    private String lastError;
}
