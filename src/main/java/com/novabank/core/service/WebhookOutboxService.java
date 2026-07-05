package com.novabank.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.model.WebhookOutboxEvent;
import com.novabank.core.repository.WebhookOutboxEventRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enqueues a webhook notification for later, asynchronous delivery instead of dispatching it
 * synchronously. See {@link WebhookOutboxEvent} for the full rationale.
 *
 * This is the only touchpoint {@code TransactionCommandService}/{@code AccountService} have with
 * the webhook subsystem now — they no longer depend on {@code WebhookService} (the HTTP-calling
 * collaborator) at all, which is itself the property {@code WebhookOutboxDecouplingTest}
 * regression-tests: a business transaction can never again be held open by a slow external HTTP
 * call, because it has no code path that could make one.
 */
@Service
@RequiredArgsConstructor
public class WebhookOutboxService {

    private final WebhookOutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Writes a pending outbox row. This is a plain local database insert (no network I/O) and
     * participates in the caller's existing transaction, giving the same atomicity guarantee as
     * the business change it accompanies (e.g. a transfer's balance update).
     *
     * Deliberately independent of {@code app.notifications.webhook.enabled} — whether webhook
     * delivery is turned on at the ops level is a {@code WebhookDispatcher} concern, not
     * something the business layer (which only knows "a large transfer happened") should have
     * to know about. If webhooks are later enabled, any backlog accumulated while disabled is
     * delivered rather than having been silently discarded at enqueue time.
     */
    @Transactional
    public void enqueue(String eventType, Map<String, Object> payload) {
        WebhookOutboxEvent event = new WebhookOutboxEvent();
        event.setEventType(eventType);
        event.setPayloadJson(serializeEnvelope(eventType, payload));
        event.setStatus(WebhookOutboxEvent.Status.PENDING);
        repository.save(event);
    }

    private String serializeEnvelope(String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            // A payload that cannot be serialized indicates a programming error in the caller
            // (e.g. a non-serializable value placed in the map), not a transient/retryable
            // failure — fail loudly here rather than silently enqueueing a broken row that
            // WebhookDispatcher could never deliver.
            throw new IllegalArgumentException(
                    "Failed to serialize webhook payload for event " + eventType, ex);
        }
    }
}
