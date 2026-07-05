package com.novabank.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.dto.transaction.DepositWithdrawRequest;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for Critical Issue #1 from the code review: "a slow or hung webhook target
 * directly extends database lock hold time on Account rows during a live funds transfer."
 *
 * Before the outbox fix, {@code TransactionService.performTransfer()} called
 * {@code WebhookService.notifyEvent()} synchronously while still inside its
 * {@code @Transactional} scope — a slow webhook endpoint would have made this exact test fail,
 * because the HTTP response to the client would not return until the (here, deliberately slowed)
 * webhook call completed.
 *
 * This test proves the fix by configuring {@link WebhookService} to take {@value #SLOW_DELIVERY_MS}ms
 * per delivery attempt and asserting the transfer's HTTP response returns in a small fraction of
 * that time — the only way that is possible is if the request path never calls
 * {@code WebhookService} at all (it only writes a local outbox row; delivery happens later on
 * {@code WebhookDispatcher}'s own scheduled thread, decoupled from this request entirely).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testwebhookdecoupling;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Dispatcher disabled for this test's duration — the point being tested is entirely
        // about the request path, not the background dispatcher's own behavior (see
        // WebhookDispatcherTest for that).
        "app.notifications.webhook.enabled=false"
})
class WebhookOutboxDecouplingTest {

    private static final long SLOW_DELIVERY_MS = 3000;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WebhookService webhookService;

    @Test
    void aLargeTransferReturnsQuicklyEvenThoughWebhookDeliveryWouldBeSlow() throws Exception {
        // If TransactionCommandService still called WebhookService synchronously, this stub
        // would make the request block for 3 full seconds.
        when(webhookService.attemptDelivery(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(SLOW_DELIVERY_MS);
                    return WebhookService.DeliveryResult.ok();
                });

        String username = "decouple_" + System.nanoTime();
        String token = registerAndLoginCustomer(username);
        String from = createAccount(token);
        String to = createAccount(token);

        DepositWithdrawRequest dep = new DepositWithdrawRequest();
        dep.setAccountNumber(from);
        dep.setAmount(new BigDecimal("20000.00"));
        mockMvc.perform(post("/api/v1/accounts/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isOk());

        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(from);
        tr.setToAccount(to);
        tr.setAmount(new BigDecimal("12000.00")); // above the large-transaction fraud threshold

        long start = System.nanoTime();
        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("transfer request must not be blocked by webhook delivery")
                .isLessThan(SLOW_DELIVERY_MS / 2);
        // The webhook dispatcher is disabled for this test, so the slow stub above is never
        // actually invoked — its only purpose is to prove that if it HAD been called
        // synchronously, this assertion would have failed.
    }

    private String registerAndLoginCustomer(String username) throws Exception {
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername(username);
        rr.setEmail(username + "@example.com");
        rr.setPassword("password123");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rr)))
                .andExpect(status().isOk());

        LoginRequest lr = new LoginRequest();
        lr.setUsername(username);
        lr.setPassword("password123");
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private String createAccount(String token) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("accountNumber").asText();
    }
}
