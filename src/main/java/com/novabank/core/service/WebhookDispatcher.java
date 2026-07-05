package com.novabank.core.service;

import com.novabank.core.config.WebhookProperties;
import com.novabank.core.model.WebhookOutboxEvent;
import com.novabank.core.repository.WebhookOutboxEventRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Delivers pending {@link WebhookOutboxEvent} rows on a fixed schedule, entirely off any HTTP
 * request thread. This is the component that actually removes the reliability risk the code
 * review flagged: {@code TransactionService}/{@code AccountService} never call
 * {@link WebhookService} directly (see {@code WebhookOutboxDecouplingTest}), so a slow or hung
 * webhook target can, at worst, slow down this background poller — it can never extend a live
 * transfer's database lock hold time, because nothing in the transfer request path makes an
 * outbound network call.
 *
 * Retry policy: a failed delivery is left/returned to {@code FAILED} with an incremented attempt
 * counter and is retried on the next poll tick as long as {@code attempts < maxAttempts}. Once
 * the ceiling is reached the row is left in {@code FAILED} permanently — a simple dead-letter
 * outcome an operator can query for (`status = 'FAILED' AND attempts >= max`) rather than a
 * silent, unbounded retry loop.
 */
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookOutboxEventRepository repository;
    private final WebhookService webhookService;
    private final WebhookProperties webhookProperties;

    @Value("${app.notifications.webhook-outbox.batch-size:20}")
    private int batchSize;

    @Value("${app.notifications.webhook-outbox.max-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${app.notifications.webhook-outbox.dispatch-interval-ms:2000}")
    public void dispatchPendingEvents() {
        if (!webhookProperties.isEnabled()) {
            // Webhooks are turned off at the ops level. Pending rows are left untouched (not
            // dropped) so enabling the feature later delivers the backlog rather than silently
            // discarding it.
            return;
        }

        List<WebhookOutboxEvent> pending = repository.findByStatusOrderByCreatedAtAsc(
                WebhookOutboxEvent.Status.PENDING, PageRequest.of(0, batchSize));
        List<WebhookOutboxEvent> retryable = repository.findByStatusOrderByCreatedAtAsc(
                WebhookOutboxEvent.Status.FAILED, PageRequest.of(0, batchSize)).stream()
                .filter(e -> e.getAttempts() < maxAttempts)
                .toList();

        pending.forEach(this::deliverAndRecordOutcome);
        retryable.forEach(this::deliverAndRecordOutcome);
    }

    @Transactional
    void deliverAndRecordOutcome(WebhookOutboxEvent event) {
        WebhookService.DeliveryResult result = webhookService.attemptDelivery(event.getEventType(), event.getPayloadJson());
        event.setAttempts(event.getAttempts() + 1);
        event.setLastAttemptAt(Instant.now());
        if (result.success()) {
            event.setStatus(WebhookOutboxEvent.Status.SENT);
            event.setLastError(null);
        } else {
            event.setStatus(WebhookOutboxEvent.Status.FAILED);
            event.setLastError(truncate(result.errorMessage()));
            if (event.getAttempts() >= maxAttempts) {
                log.warn("Webhook outbox event {} ({}) reached max attempts ({}); leaving as FAILED (dead-letter).",
                        event.getId(), event.getEventType(), maxAttempts);
            }
        }
        repository.save(event);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
