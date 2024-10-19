package com.novabank.core;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:teststatement;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TransactionStatementControllerTests {

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
    void statementEndpointReturnsCsv() throws Exception {
        String username = "statement_" + System.nanoTime();
        String token = registerAndLoginCustomer(username);
        String a1 = createAccount(token);
        String a2 = createAccount(token);

        DepositWithdrawRequest dep = new DepositWithdrawRequest();
        dep.setAccountNumber(a1);
        dep.setAmount(new BigDecimal("900.00"));
        dep.setNote("salary");
        mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isOk());

        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(a1);
        tr.setToAccount(a2);
        tr.setAmount(new BigDecimal("250.00"));
        tr.setNote("savings move");
        mockMvc.perform(post("/api/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isOk());

        MvcResult statement = mockMvc.perform(get("/api/transactions/statement")
                        .header("Authorization", "Bearer " + token)
                        .param("sort", "occurredAt,asc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"transaction-statement.csv\""))
                .andReturn();

        String csv = statement.getResponse().getContentAsString();
        assertThat(csv).startsWith("reference,type,amount,fromAccount,toAccount,occurredAt,note");
        assertThat(csv).contains("DEPOSIT");
        assertThat(csv).contains("TRANSFER");
        assertThat(csv).contains("savings move");
    }
}
