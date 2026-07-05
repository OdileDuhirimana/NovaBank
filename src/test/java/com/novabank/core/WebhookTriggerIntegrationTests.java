package com.novabank.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.dto.transaction.DepositWithdrawRequest;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.WebhookOutboxEvent;
import com.novabank.core.repository.WebhookOutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that a webhook-worthy business event (a large transfer, an account freeze) results in
 * a {@link WebhookOutboxEvent} row rather than a synchronous HTTP call made from inside the
 * request/transaction — this is the regression coverage for the fix to Critical Issue #1 (a
 * blocking webhook call previously made from inside {@code TransactionService.performTransfer()}
 * while its {@code @Transactional} scope was still open).
 *
 * Deliberately does NOT mock {@code WebhookService} (there is nothing to mock: the business
 * services have no dependency on it at all anymore — see {@code WebhookOutboxDecouplingTest} for
 * a Mockito-based proof of exactly that). Instead this test asserts against the real, persisted
 * side effect the business method actually produces.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testwebhooktrigger;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Keep the real WebhookDispatcher from racing this test's assertions during the test
        // window and from spamming warnings about an unconfigured webhook URL.
        "app.notifications.webhook.enabled=false"
})
class WebhookTriggerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WebhookOutboxEventRepository webhookOutboxEventRepository;

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

    private String loginAdmin() throws Exception {
        LoginRequest lr = new LoginRequest();
        lr.setUsername("admin");
        lr.setPassword("admin12345");
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

    @Test
    void largeTransferEnqueuesAWebhookOutboxEventInsteadOfCallingOutSynchronously() throws Exception {
        String username = "webhook_tx_" + System.nanoTime();
        String token = registerAndLoginCustomer(username);
        String from = createAccount(token);
        String to = createAccount(token);

        DepositWithdrawRequest dep = new DepositWithdrawRequest();
        dep.setAccountNumber(from);
        dep.setAmount(new BigDecimal("20000.00"));
        dep.setNote("seed");
        mockMvc.perform(post("/api/v1/accounts/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isOk());

        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(from);
        tr.setToAccount(to);
        tr.setAmount(new BigDecimal("12000.00"));
        tr.setNote("large");
        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isOk());

        List<WebhookOutboxEvent> events = webhookOutboxEventRepository.findAll();
        WebhookOutboxEvent event = events.stream()
                .filter(e -> "LARGE_TRANSFER".equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a LARGE_TRANSFER outbox event, found: " + events));

        assertThat(event.getStatus()).isEqualTo(WebhookOutboxEvent.Status.PENDING);
        JsonNode envelope = objectMapper.readTree(event.getPayloadJson());
        assertThat(envelope.get("payload").get("fromAccount").asText()).isEqualTo(from);
        assertThat(envelope.get("payload").get("toAccount").asText()).isEqualTo(to);
    }

    @Test
    void accountFreezeEnqueuesAWebhookOutboxEvent() throws Exception {
        String username = "webhook_freeze_" + System.nanoTime();
        String customerToken = registerAndLoginCustomer(username);
        String adminToken = loginAdmin();
        String accountNumber = createAccount(customerToken);

        mockMvc.perform(patch("/api/v1/admin/accounts/{accountNumber}/status", accountNumber)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false,\"reason\":\"manual review\"}"))
                .andExpect(status().isOk());

        List<WebhookOutboxEvent> events = webhookOutboxEventRepository.findAll();
        WebhookOutboxEvent event = events.stream()
                .filter(e -> "ACCOUNT_FROZEN".equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an ACCOUNT_FROZEN outbox event, found: " + events));

        JsonNode envelope = objectMapper.readTree(event.getPayloadJson());
        assertThat(envelope.get("payload").get("accountNumber").asText()).isEqualTo(accountNumber);
    }
}
