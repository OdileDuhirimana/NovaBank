package com.novabank.core.unit;

import com.novabank.core.config.WebhookProperties;
import com.novabank.core.model.WebhookOutboxEvent;
import com.novabank.core.repository.WebhookOutboxEventRepository;
import com.novabank.core.service.WebhookDispatcher;
import com.novabank.core.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit test for {@link WebhookDispatcher} — no Spring context, no scheduler, no
 * database. Directly regression-tests the retry/dead-letter contract described in the class
 * Javadoc: a successful delivery marks the row SENT; a failed delivery increments the attempt
 * counter and records the error, staying retryable until {@code maxAttempts} is reached.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock
    private WebhookOutboxEventRepository repository;
    @Mock
    private WebhookService webhookService;
    @Mock
    private WebhookProperties webhookProperties;

    private WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new WebhookDispatcher(repository, webhookService, webhookProperties);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 20);
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 5);
    }

    @Test
    void doesNothingWhenWebhookDeliveryIsDisabledAtTheOpsLevel() {
        when(webhookProperties.isEnabled()).thenReturn(false);

        dispatcher.dispatchPendingEvents();

        verify(repository, never()).findByStatusOrderByCreatedAtAsc(any(), any());
        verify(webhookService, never()).attemptDelivery(anyString(), anyString());
    }

    @Test
    void successfulDeliveryMarksTheEventSent() {
        WebhookOutboxEvent event = pendingEvent("LARGE_TRANSFER", "{}");
        when(webhookProperties.isEnabled()).thenReturn(true);
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.PENDING), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.FAILED), any(Pageable.class)))
                .thenReturn(List.of());
        when(webhookService.attemptDelivery("LARGE_TRANSFER", "{}"))
                .thenReturn(WebhookService.DeliveryResult.ok());

        dispatcher.dispatchPendingEvents();

        assertThat(event.getStatus()).isEqualTo(WebhookOutboxEvent.Status.SENT);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastAttemptAt()).isNotNull();
        verify(repository).save(event);
    }

    @Test
    void failedDeliveryIncrementsAttemptsAndRecordsTheError() {
        WebhookOutboxEvent event = pendingEvent("LARGE_TRANSFER", "{}");
        when(webhookProperties.isEnabled()).thenReturn(true);
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.PENDING), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.FAILED), any(Pageable.class)))
                .thenReturn(List.of());
        when(webhookService.attemptDelivery("LARGE_TRANSFER", "{}"))
                .thenReturn(WebhookService.DeliveryResult.failure("connection refused"));

        dispatcher.dispatchPendingEvents();

        assertThat(event.getStatus()).isEqualTo(WebhookOutboxEvent.Status.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("connection refused");
    }

    @Test
    void retriesAPreviouslyFailedEventUnderTheAttemptCeiling() {
        WebhookOutboxEvent event = pendingEvent("ACCOUNT_FROZEN", "{}");
        event.setStatus(WebhookOutboxEvent.Status.FAILED);
        event.setAttempts(2);
        when(webhookProperties.isEnabled()).thenReturn(true);
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.PENDING), any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.FAILED), any(Pageable.class)))
                .thenReturn(List.of(event));
        when(webhookService.attemptDelivery("ACCOUNT_FROZEN", "{}"))
                .thenReturn(WebhookService.DeliveryResult.ok());

        dispatcher.dispatchPendingEvents();

        assertThat(event.getStatus()).isEqualTo(WebhookOutboxEvent.Status.SENT);
        assertThat(event.getAttempts()).isEqualTo(3);
    }

    @Test
    void aFailedEventAtTheAttemptCeilingIsExcludedFromFurtherProcessing() {
        WebhookOutboxEvent exhausted = pendingEvent("ACCOUNT_FROZEN", "{}");
        exhausted.setStatus(WebhookOutboxEvent.Status.FAILED);
        exhausted.setAttempts(5); // == maxAttempts configured in setUp()
        when(webhookProperties.isEnabled()).thenReturn(true);
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.PENDING), any(Pageable.class)))
                .thenReturn(List.of());
        // The repository query itself has no attempts filter — WebhookDispatcher filters
        // client-side, which is exactly what this test proves.
        when(repository.findByStatusOrderByCreatedAtAsc(eq(WebhookOutboxEvent.Status.FAILED), any(Pageable.class)))
                .thenReturn(List.of(exhausted));

        dispatcher.dispatchPendingEvents();

        verify(webhookService, never()).attemptDelivery(anyString(), anyString());
        verify(repository, never()).save(exhausted);
    }

    private WebhookOutboxEvent pendingEvent(String eventType, String payloadJson) {
        WebhookOutboxEvent event = new WebhookOutboxEvent();
        event.setEventType(eventType);
        event.setPayloadJson(payloadJson);
        event.setStatus(WebhookOutboxEvent.Status.PENDING);
        return event;
    }
}
