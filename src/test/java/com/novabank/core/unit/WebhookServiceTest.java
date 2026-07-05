package com.novabank.core.unit;

import com.novabank.core.config.WebhookProperties;
import com.novabank.core.service.WebhookService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Isolated unit tests for {@link WebhookService}, updated for the outbox-based delivery model:
 * this class no longer decides whether/what to send (that's {@code WebhookOutboxService}'s job)
 * — it only attempts a single delivery of an already-serialized envelope and reports success or
 * failure via {@link WebhookService.DeliveryResult}, never throwing.
 *
 * The one test that exercises the actual HTTP call uses a real, ephemeral, in-process
 * {@link HttpServer} (JDK built-in, no extra test dependency) bound to an OS-assigned localhost
 * port rather than mocking Java's {@code HttpClient}, which has no seams Mockito can use cleanly.
 */
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private WebhookProperties webhookProperties;

    private WebhookService webhookService;
    private HttpServer testServer;

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(webhookProperties);
    }

    @AfterEach
    void tearDown() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    @Test
    void reportsFailureWhenUrlIsBlank() {
        when(webhookProperties.getUrl()).thenReturn("   ");

        WebhookService.DeliveryResult result = webhookService.attemptDelivery("LARGE_TRANSFER", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void sendsThePreSerializedEnvelopeExactlyAsProvided()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedApiKeyHeader = new AtomicReference<>();
        CompletableFuture<Void> requestReceived = new CompletableFuture<>();

        testServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        testServer.createContext("/webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(body, StandardCharsets.UTF_8));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedApiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            requestReceived.complete(null);
        });
        testServer.start();
        int port = testServer.getAddress().getPort();

        when(webhookProperties.getUrl()).thenReturn("http://localhost:" + port + "/webhook");
        when(webhookProperties.getApiKey()).thenReturn("secret-api-key");
        when(webhookProperties.getConnectTimeoutMs()).thenReturn(2000L);
        when(webhookProperties.getReadTimeoutMs()).thenReturn(2000L);

        String envelopeJson = "{\"eventType\":\"LARGE_TRANSFER\",\"payload\":{\"actor\":\"alice\"}}";
        WebhookService.DeliveryResult result = webhookService.attemptDelivery("LARGE_TRANSFER", envelopeJson);

        requestReceived.get(5, TimeUnit.SECONDS);

        assertThat(result.success()).isTrue();
        assertThat(capturedContentType.get()).isEqualTo("application/json");
        assertThat(capturedApiKeyHeader.get()).isEqualTo("secret-api-key");
        assertThat(capturedBody.get()).isEqualTo(envelopeJson);
    }

    @Test
    void reportsFailureRatherThanThrowingWhenTargetRespondsWithAnErrorStatus()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Void> requestReceived = new CompletableFuture<>();
        testServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        testServer.createContext("/webhook", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            requestReceived.complete(null);
        });
        testServer.start();
        int port = testServer.getAddress().getPort();

        when(webhookProperties.getUrl()).thenReturn("http://localhost:" + port + "/webhook");
        when(webhookProperties.getConnectTimeoutMs()).thenReturn(2000L);
        when(webhookProperties.getReadTimeoutMs()).thenReturn(2000L);

        WebhookService.DeliveryResult result = webhookService.attemptDelivery("LARGE_TRANSFER", "{}");
        requestReceived.get(5, TimeUnit.SECONDS);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void reportsFailureRatherThanThrowingWhenTargetIsUnreachable() {
        // Port 1 is a reserved/unroutable port practically guaranteed to refuse the connection
        // immediately, simulating an unreachable webhook endpoint.
        when(webhookProperties.getUrl()).thenReturn("http://localhost:1/webhook");
        when(webhookProperties.getConnectTimeoutMs()).thenReturn(500L);
        when(webhookProperties.getReadTimeoutMs()).thenReturn(500L);

        // Must not propagate — WebhookDispatcher relies on this never throwing.
        WebhookService.DeliveryResult result = webhookService.attemptDelivery("LARGE_TRANSFER", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
    }
}
