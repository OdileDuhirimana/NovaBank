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
        "spring.datasource.url=jdbc:h2:mem:testsummary;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TransactionSummaryControllerTests {

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
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accountNumber").asText();
    }

    @Test
    void accountScopedSummaryCalculatesCashflow() throws Exception {
        String username = "summary_" + System.nanoTime();
        String token = registerAndLoginCustomer(username);
        String accountA = createAccount(token);
        String accountB = createAccount(token);

        DepositWithdrawRequest deposit = new DepositWithdrawRequest();
        deposit.setAccountNumber(accountA);
        deposit.setAmount(new BigDecimal("1000.00"));
        deposit.setNote("salary");
        mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deposit)))
                .andExpect(status().isOk());

        DepositWithdrawRequest withdraw = new DepositWithdrawRequest();
        withdraw.setAccountNumber(accountA);
        withdraw.setAmount(new BigDecimal("200.00"));
        withdraw.setNote("cash");
        mockMvc.perform(post("/api/accounts/withdraw")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdraw)))
                .andExpect(status().isOk());

        TransferRequest transfer = new TransferRequest();
        transfer.setFromAccount(accountA);
        transfer.setToAccount(accountB);
        transfer.setAmount(new BigDecimal("300.00"));
        transfer.setNote("move");
        mockMvc.perform(post("/api/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isOk());

        MvcResult summaryResult = mockMvc.perform(get("/api/transactions/summary")
                        .header("Authorization", "Bearer " + token)
                        .param("accountNumber", accountA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(summaryResult.getResponse().getContentAsString());
        assertThat(body.get("scopeAccountNumber").asText()).isEqualTo(accountA);
        assertThat(new BigDecimal(body.get("totalCredits").asText())).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(body.get("totalDebits").asText())).isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(body.get("netCashflow").asText())).isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(body.get("largestDebit").asText())).isEqualByComparingTo("300.00");
    }

    @Test
    void otherUserAccountSummaryIsForbidden() throws Exception {
        String user1 = "summary_u1_" + System.nanoTime();
        String user2 = "summary_u2_" + System.nanoTime();
        String token1 = registerAndLoginCustomer(user1);
        String token2 = registerAndLoginCustomer(user2);

        String otherAccount = createAccount(token2);

        mockMvc.perform(get("/api/transactions/summary")
                        .header("Authorization", "Bearer " + token1)
                        .param("accountNumber", otherAccount))
                .andExpect(status().isForbidden());
    }
}
