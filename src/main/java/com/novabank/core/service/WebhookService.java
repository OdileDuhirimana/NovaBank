package com.novabank.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookProperties webhookProperties;
    private final ObjectMapper objectMapper;

    public void notifyEvent(String eventType, Map<String, Object> payload) {
        if (!webhookProperties.isEnabled()) {
            return;
        }
        String url = webhookProperties.getUrl();
        if (url == null || url.isBlank()) {
            log.warn("Webhook is enabled but no URL is configured. Skipping event {}", eventType);
            return;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);

        try {
            String body = objectMapper.writeValueAsString(envelope);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(webhookProperties.getConnectTimeoutMs()))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(webhookProperties.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            String apiKey = webhookProperties.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("X-Api-Key", apiKey);
            }

            HttpResponse<Void> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                log.warn("Webhook call failed with status {} for event {}", response.statusCode(), eventType);
            }
        } catch (Exception ex) {
            log.warn("Webhook call failed for event {}: {}", eventType, ex.getMessage());
        }
    }
}
