package com.novabank.core.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.model.WebhookOutboxEvent;
import com.novabank.core.repository.WebhookOutboxEventRepository;
import com.novabank.core.service.WebhookOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Isolated unit test for {@link WebhookOutboxService} — no Spring context, no database. Verifies
 * the outbox row is written with the full envelope (eventType/occurredAt/payload) and always
 * starts life as PENDING, and that enqueueing never depends on whether webhook delivery is
 * currently enabled (that check belongs to {@code WebhookDispatcher}, not here — see the
 * Javadoc on {@code WebhookOutboxService.enqueue}).
 */
@ExtendWith(MockitoExtension.class)
class WebhookOutboxServiceTest {

    @Mock
    private WebhookOutboxEventRepository repository;

    private WebhookOutboxService outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new WebhookOutboxService(repository, new ObjectMapper());
    }

    @Test
    void enqueueWritesAPendingRowContainingTheFullEnvelope() throws Exception {
        outboxService.enqueue("LARGE_TRANSFER", Map.of("amount", "15000.00", "actor", "alice"));

        ArgumentCaptor<WebhookOutboxEvent> captor = ArgumentCaptor.forClass(WebhookOutboxEvent.class);
        verify(repository).save(captor.capture());
        WebhookOutboxEvent saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo("LARGE_TRANSFER");
        assertThat(saved.getStatus()).isEqualTo(WebhookOutboxEvent.Status.PENDING);
        assertThat(saved.getAttempts()).isZero();

        JsonNode envelope = new ObjectMapper().readTree(saved.getPayloadJson());
        assertThat(envelope.get("eventType").asText()).isEqualTo("LARGE_TRANSFER");
        assertThat(envelope.has("occurredAt")).isTrue();
        assertThat(envelope.get("payload").get("amount").asText()).isEqualTo("15000.00");
        assertThat(envelope.get("payload").get("actor").asText()).isEqualTo("alice");
    }

    @Test
    void rejectsAPayloadThatCannotBeSerializedRatherThanEnqueueingABrokenRow() {
        // A raw Object reference is not Jackson-serializable in a way that round-trips cleanly
        // for this test's purposes — using a self-referencing structure to force a failure.
        Map<String, Object> circular = new java.util.HashMap<>();
        circular.put("self", circular);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> outboxService.enqueue("LARGE_TRANSFER", circular))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
