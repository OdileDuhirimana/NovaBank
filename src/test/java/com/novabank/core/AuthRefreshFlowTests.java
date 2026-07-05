package com.novabank.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration coverage for the refresh-token endpoints added to close Critical Issue
 * #5 (no server-side kill switch for a leaked access token). Exercises the real HTTP request
 * cycle through {@code AuthController} -> {@code UserService} -> {@code RefreshTokenService} ->
 * a real (H2) database, not mocked collaborators.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testauthrefresh;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthRefreshFlowTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode registerAndLogin(String username) throws Exception {
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
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void loginReturnsBothAnAccessTokenAndARefreshToken() throws Exception {
        JsonNode auth = registerAndLogin("refreshflow_" + System.nanoTime());

        assertThat(auth.get("token").asText()).isNotBlank();
        assertThat(auth.get("refreshToken").asText()).isNotBlank();
    }

    @Test
    void refreshExchangesAValidRefreshTokenForANewPair() throws Exception {
        JsonNode auth = registerAndLogin("refreshflow_" + System.nanoTime());
        String originalRefreshToken = auth.get("refreshToken").asText();

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rotated = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        assertThat(rotated.get("token").asText()).isNotBlank();
        assertThat(rotated.get("refreshToken").asText()).isNotBlank();
        assertThat(rotated.get("refreshToken").asText()).isNotEqualTo(originalRefreshToken);
    }

    @Test
    void reusingAnAlreadyRotatedRefreshTokenIsRejected() throws Exception {
        JsonNode auth = registerAndLogin("refreshflow_" + System.nanoTime());
        String originalRefreshToken = auth.get("refreshToken").asText();

        // First use rotates it successfully.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRefreshToken + "\"}"))
                .andExpect(status().isOk());

        // Reusing the same (now-revoked) token must be rejected, not silently accepted.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRefreshToken + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesTheRefreshTokenSoItCanNoLongerBeUsed() throws Exception {
        JsonNode auth = registerAndLogin("refreshflow_" + System.nanoTime());
        String refreshToken = auth.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutIsIdempotentAndNeverLeaksWhetherATokenWasValid() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"never-issued-token-value\"}"))
                .andExpect(status().isNoContent());
    }
}
