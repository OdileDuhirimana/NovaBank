package com.novabank.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.core.dto.auth.RegisterRequest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the N+1 query pattern found in the code review:
 * {@code AdminController.accounts()} mapped each {@code Account} to an
 * {@code AdminAccountResponse} by calling {@code a.getUser().getUsername()} per row, and
 * {@code Account.user} is lazily fetched — with no {@code @EntityGraph}/fetch-join, listing a
 * page of N accounts issued 1 (page query) + N (one lazy-load per row) queries.
 *
 * This test proves the fix ({@code @EntityGraph(attributePaths = "user")} added to
 * {@code AccountRepository}'s listing methods) by using Hibernate's {@link Statistics} API to
 * count actual SQL queries executed while listing several accounts belonging to different
 * users, and asserting the query count stays flat (small, constant) rather than scaling with
 * the number of accounts returned.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testnplus1;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class AdminAccountNPlusOneTests {

    private static final int ACCOUNT_COUNT = 5;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String registerAndLoginCustomer(String username) throws Exception {
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername(username);
        rr.setEmail(username + "@example.com");
        rr.setPassword("password123");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rr)))
                .andExpect(status().isOk());

        var lr = new com.novabank.core.dto.auth.LoginRequest();
        lr.setUsername(username);
        lr.setPassword("password123");
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String loginAdmin() throws Exception {
        var lr = new com.novabank.core.dto.auth.LoginRequest();
        lr.setUsername("admin");
        lr.setPassword("admin12345");
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lr)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void listingAccountsFromDifferentOwnersDoesNotTriggerOnePlusNQueries() throws Exception {
        // Create ACCOUNT_COUNT accounts, each owned by a distinct user, to guarantee that a
        // naive lazy-load-per-row implementation would need a distinct extra query per row
        // (same-owner accounts could otherwise be served from Hibernate's first-level cache,
        // masking the bug).
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            String username = "nplus1owner" + i + "_" + System.nanoTime();
            String token = registerAndLoginCustomer(username);
            mockMvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        String adminToken = loginAdmin();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();

        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("size", String.valueOf(ACCOUNT_COUNT + 10)))
                .andExpect(status().isOk());

        long queryCount = statistics.getPrepareStatementCount();

        // Without the @EntityGraph fix, this would be at least 1 (page query) + ACCOUNT_COUNT
        // (one per-row lazy user fetch) = 6+ queries, and would keep growing with more accounts.
        // With the fix, it stays small and flat regardless of how many accounts are returned.
        assertThat(queryCount)
                .as("SQL statement count when listing %d accounts from distinct owners", ACCOUNT_COUNT)
                .isLessThan(ACCOUNT_COUNT);
    }
}
