package com.novabank.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the missing-rate-limiting finding in the code review: /api/auth/login and
 * /api/auth/register were fully open to unlimited attempts with no throttling, making
 * credential-stuffing and registration-spam trivial. AuthRateLimitFilter now caps each client IP
 * to a small number of requests per minute on these two endpoints; this test proves the limit is
 * actually enforced by exceeding it and asserting a 429 response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testratelimit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Re-enable the filter for this test class only — see src/test/resources/application.yml
        // for why it is disabled globally across the rest of the suite.
        "app.security.auth-rate-limit.enabled=true"
})
class AuthRateLimitTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void repeatedLoginAttemptsFromSameClientAreEventuallyRateLimited() throws Exception {
        LoginRequest lr = new LoginRequest();
        lr.setUsername("no-such-user-" + System.nanoTime());
        lr.setPassword("wrong-password");
        String payload = objectMapper.writeValueAsString(lr);

        int rateLimitedCount = 0;
        // The bucket capacity is 20/minute; firing well beyond that from the same simulated
        // client IP must trigger at least one 429 response.
        for (int i = 0; i < 30; i++) {
            var result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", "203.0.113.42")
                            .content(payload))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                rateLimitedCount++;
            }
        }

        org.assertj.core.api.Assertions.assertThat(rateLimitedCount).isGreaterThan(0);
    }

    @Test
    void differentClientIpsAreRateLimitedIndependently() throws Exception {
        LoginRequest lr = new LoginRequest();
        lr.setUsername("no-such-user-" + System.nanoTime());
        lr.setPassword("wrong-password");
        String payload = objectMapper.writeValueAsString(lr);

        // Exhaust the bucket for one IP.
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Forwarded-For", "198.51.100.7")
                    .content(payload));
        }

        // A different client IP must still be allowed through to the authentication logic
        // (i.e. not globally rate limited), proving the limiter is keyed per-client.
        var result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.8")
                        .content(payload))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isNotEqualTo(429);
    }
}
