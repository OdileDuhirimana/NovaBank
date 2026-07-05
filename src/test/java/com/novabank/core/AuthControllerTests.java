package com.novabank.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.model.Role;
import com.novabank.core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testweb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;

    @Test
    void registerAndLogin() throws Exception {
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername("webuser");
        rr.setEmail("webuser@example.com");
        rr.setPassword("password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rr)))
                .andExpect(status().isOk());

        LoginRequest lr = new LoginRequest();
        lr.setUsername("webuser");
        lr.setPassword("password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk());
    }

    /**
     * Regression test for the privilege-escalation vulnerability found in the code review:
     * RegisterRequest previously exposed a client-settable "role" field with no server-side
     * restriction, so POSTing {"role":"ADMIN", ...} to the public registration endpoint would
     * self-provision an administrator account. RegisterRequest no longer has a role field at
     * all, so even a raw JSON payload that includes a "role" key must be silently ignored by
     * Jackson deserialization, and the persisted user must always end up as CUSTOMER.
     */
    @Test
    void registrationIgnoresClientSuppliedAdminRoleField() throws Exception {
        String username = "attacker_" + System.nanoTime();
        String rawPayloadWithRoleField = "{"
                + "\"username\":\"" + username + "\","
                + "\"email\":\"" + username + "@example.com\","
                + "\"password\":\"password123\","
                + "\"role\":\"ADMIN\""
                + "}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rawPayloadWithRoleField))
                .andExpect(status().isOk());

        var persisted = userRepository.findByUsername(username).orElseThrow();
        assertThat(persisted.getRole()).isEqualTo(Role.CUSTOMER);
    }
}
