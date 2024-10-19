package com.novabank.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.dto.transaction.DepositWithdrawRequest;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testidem;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TransferIdempotencyControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndLoginCustomer(String username) throws Exception {
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername(username);
        rr.setEmail(username + "@example.com");
        rr.setPassword("password123");
        rr.setRole(Role.CUSTOMER);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rr)))
                .andExpect(status().isOk());

        LoginRequest lr = new LoginRequest();
        lr.setUsername(username);
        lr.setPassword("password123");
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String createAccount(String token) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("accountNumber").asText();
    }

    @Test
    void transferRetryWithSameIdempotencyKeyDoesNotDoubleCharge() throws Exception {
        String username = "idem_" + System.nanoTime();
        String token = registerAndLoginCustomer(username);
        String from = createAccount(token);
        String to = createAccount(token);

        DepositWithdrawRequest dep = new DepositWithdrawRequest();
        dep.setAccountNumber(from);
        dep.setAmount(new BigDecimal("500.00"));
        dep.setNote("seed");
        mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isOk());

        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(from);
        tr.setToAccount(to);
        tr.setAmount(new BigDecimal("100.00"));
        tr.setNote("first move");

        MvcResult first = mockMvc.perform(post("/api/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isOk())
                .andReturn();

        String ref1 = objectMapper.readTree(first.getResponse().getContentAsString()).get("reference").asText();
        String ref2 = objectMapper.readTree(second.getResponse().getContentAsString()).get("reference").asText();
        assertThat(ref2).isEqualTo(ref1);

        MvcResult accounts = mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = objectMapper.readTree(accounts.getResponse().getContentAsString());
        BigDecimal fromBalance = null;
        BigDecimal toBalance = null;
        for (JsonNode node : arr) {
            if (from.equals(node.get("accountNumber").asText())) {
                fromBalance = node.get("balance").decimalValue();
            } else if (to.equals(node.get("accountNumber").asText())) {
                toBalance = node.get("balance").decimalValue();
            }
        }
        assertThat(fromBalance).isEqualByComparingTo("400.00");
        assertThat(toBalance).isEqualByComparingTo("100.00");
    }

    @Test
    void idempotencyKeyReuseWithDifferentPayloadFails() throws Exception {
        String username = "idem2_" + System.nanoTime();
        String token = registerAndLoginCustomer(username);
        String from = createAccount(token);
        String to = createAccount(token);

        DepositWithdrawRequest dep = new DepositWithdrawRequest();
        dep.setAccountNumber(from);
        dep.setAmount(new BigDecimal("500.00"));
        dep.setNote("seed");
        mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isOk());

        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(from);
        tr.setToAccount(to);
        tr.setAmount(new BigDecimal("50.00"));
        tr.setNote("move");
        mockMvc.perform(post("/api/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "idem-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isOk());

        tr.setAmount(new BigDecimal("70.00"));
        MvcResult fail = mockMvc.perform(post("/api/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "idem-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = objectMapper.readTree(fail.getResponse().getContentAsString());
        assertThat(body.get("message").asText()).contains("different transfer payload");
    }
}
