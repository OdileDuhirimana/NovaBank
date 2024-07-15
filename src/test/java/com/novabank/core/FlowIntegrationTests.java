package com.novabank.core;

import com.novabank.core.dto.auth.LoginRequest;
import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.Role;
import com.novabank.core.model.User;
import com.novabank.core.service.AccountService;
import com.novabank.core.service.TransactionService;
import com.novabank.core.service.UserService;
import com.novabank.core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class FlowIntegrationTests {

    @Autowired
    private UserService userService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private UserRepository userRepository;

    @Test
    void endToEndFlow_register_deposit_transfer() {
        // Register user
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername("alice");
        rr.setEmail("alice@example.com");
        rr.setPassword("password123");
        rr.setRole(Role.CUSTOMER);
        var authResp = userService.register(rr);
        assertThat(authResp.getToken()).isNotBlank();

        // Login
        LoginRequest lr = new LoginRequest();
        lr.setUsername("alice");
        lr.setPassword("password123");
        var loginResp = userService.login(lr);
        assertThat(loginResp.getToken()).isNotBlank();

        // Load persisted principal user (with ID)
        User user = userRepository.findByUsername("alice").orElseThrow();
        // Create account A
        var accA = accountService.createAccount(user);
        assertThat(accA.getBalance()).isEqualByComparingTo("0.00");
        // Deposit into A
        var accAAfter = accountService.deposit(user, accA.getAccountNumber(), new BigDecimal("150.00"), "init");
        assertThat(accAAfter.getBalance()).isEqualByComparingTo("150.00");

        // Create account B
        var accB = accountService.createAccount(user);
        assertThat(accB.getBalance()).isEqualByComparingTo("0.00");

        // Transfer 50 from A to B
        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(accA.getAccountNumber());
        tr.setToAccount(accB.getAccountNumber());
        tr.setAmount(new BigDecimal("50.00"));
        tr.setNote("move funds");
        String ref = transactionService.transfer(user, tr);
        assertThat(ref).isNotBlank();
    }
}
