package com.novabank.core.service;

import com.novabank.core.config.WebhookProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The only collaborator in this codebase that makes an outbound HTTP call to notify an external
 * webhook endpoint. Deliberately has exactly one caller now: {@code WebhookDispatcher}, running
 * on its own scheduled thread, never on a request thread or inside a business transaction — see
 * {@link com.novabank.core.model.WebhookOutboxEvent} for the full rationale.
 */
@Service
@RequiredArgsConstructor
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookProperties webhookProperties;

    /**
     * Result of a single delivery attempt, carrying enough detail for the caller (
     * {@code WebhookDispatcher}) to record what happened without this class knowing anything
     * about the outbox/retry model.
     */
    public record DeliveryResult(boolean success, String errorMessage) {
        public static DeliveryResult ok() {
            return new DeliveryResult(true, null);
        }

        public static DeliveryResult failure(String message) {
            return new DeliveryResult(false, message);
        }
    }

    /**
     * Attempts a single synchronous delivery of an already-serialized webhook envelope. Never
     * throws — every failure mode (missing config, network error, non-2xx/3xx response) is
     * captured in the returned {@link DeliveryResult} so the caller can decide how to record
     * and/or retry it.
     *
     * @param eventType    used only for logging.
     * @param envelopeJson the exact JSON body to POST, already fully formed (see
     *                     {@code WebhookOutboxService#enqueue}).
     */
    public DeliveryResult attemptDelivery(String eventType, String envelopeJson) {
        String url = webhookProperties.getUrl();
        if (url == null || url.isBlank()) {
            log.warn("Webhook is enabled but no URL is configured. Skipping event {}", eventType);
            return DeliveryResult.failure("No webhook URL configured");
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(webhookProperties.getConnectTimeoutMs()))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(webhookProperties.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(envelopeJson));

            String apiKey = webhookProperties.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("X-Api-Key", apiKey);
            }

            HttpResponse<Void> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                String message = "Webhook call failed with status " + response.statusCode();
                log.warn("{} for event {}", message, eventType);
                return DeliveryResult.failure(message);
            }
            return DeliveryResult.ok();
        } catch (Exception ex) {
            log.warn("Webhook call failed for event {}: {}", eventType, ex.getMessage());
            return DeliveryResult.failure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
