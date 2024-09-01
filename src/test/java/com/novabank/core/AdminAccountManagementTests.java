package com.novabank.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testadminacct;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminAccountManagementTests {

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

    private String loginAdmin() throws Exception {
        LoginRequest lr = new LoginRequest();
        lr.setUsername("admin");
        lr.setPassword("admin12345");
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void adminCanFreezeAccountAndBlockTransactions() throws Exception {
        String username = "freezeuser_" + System.nanoTime();
        String customerToken = registerAndLoginCustomer(username);
        String adminToken = loginAdmin();

        MvcResult created = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andReturn();
        String accountNumber = objectMapper.readTree(created.getResponse().getContentAsString()).get("accountNumber").asText();

        mockMvc.perform(patch("/api/admin/accounts/{accountNumber}/status", accountNumber)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false,\"reason\":\"suspicious activity\"}"))
                .andExpect(status().isOk());

        MvcResult depositResult = mockMvc.perform(post("/api/accounts/deposit")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"" + accountNumber + "\",\"amount\":50,\"note\":\"retry\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = objectMapper.readTree(depositResult.getResponse().getContentAsString());
        assertThat(error.get("message").asText()).contains("inactive");
    }

    @Test
    void adminCanListAccountsWithFilters() throws Exception {
        String username = "adminlist_" + System.nanoTime();
        String customerToken = registerAndLoginCustomer(username);
        String adminToken = loginAdmin();

        MvcResult created = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andReturn();
        String accountNumber = objectMapper.readTree(created.getResponse().getContentAsString()).get("accountNumber").asText();

        MvcResult list = mockMvc.perform(get("/api/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("active", "true")
                        .param("username", username))
                .andExpect(status().isOk())
                .andReturn();

        String body = list.getResponse().getContentAsString();
        assertThat(body).contains(accountNumber);
        assertThat(body).contains(username);
    }

    @Test
    void customerCannotUpdateAccountStatus() throws Exception {
        String username = "noadmin_" + System.nanoTime();
        String customerToken = registerAndLoginCustomer(username);

        MvcResult created = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andReturn();
        String accountNumber = objectMapper.readTree(created.getResponse().getContentAsString()).get("accountNumber").asText();

        mockMvc.perform(patch("/api/admin/accounts/{accountNumber}/status", accountNumber)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
    }
}
