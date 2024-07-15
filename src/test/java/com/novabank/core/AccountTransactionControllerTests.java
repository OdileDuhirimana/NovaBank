package com.novabank.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.datasource.url=jdbc:h2:mem:testacct;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AccountTransactionControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndLogin(String username) throws Exception {
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername(username);
        rr.setEmail(username + "@example.com");
        rr.setPassword("password123");
        rr.setRole(Role.CUSTOMER);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rr)))
                .andExpect(status().isOk());
        var lr = new com.novabank.core.dto.auth.LoginRequest();
        lr.setUsername(username);
        lr.setPassword("password123");
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    @Test
    void accountAndTransactionFlow() throws Exception {
        String token = registerAndLogin("mvcuser");
        // Create account A
        MvcResult resA = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String accA = objectMapper.readTree(resA.getResponse().getContentAsString()).get("accountNumber").asText();
        // Deposit 200 into A
        DepositWithdrawRequest dep = new DepositWithdrawRequest();
        dep.setAccountNumber(accA);
        dep.setAmount(new BigDecimal("200.00"));
        dep.setNote("seed");
        mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isOk());
        // Create account B
        MvcResult resB = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String accB = objectMapper.readTree(resB.getResponse().getContentAsString()).get("accountNumber").asText();
        // Transfer 75 from A to B
        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(accA);
        tr.setToAccount(accB);
        tr.setAmount(new BigDecimal("75.00"));
        tr.setNote("move");
        mockMvc.perform(post("/api/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tr)))
                .andExpect(status().isOk());
        // Fetch my transactions
        MvcResult my = mockMvc.perform(get("/api/transactions/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(my.getResponse().getContentAsString()).isArray()).isTrue();
    }
}
