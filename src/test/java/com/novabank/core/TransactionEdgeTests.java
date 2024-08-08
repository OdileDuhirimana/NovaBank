package com.novabank.core;

import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.Role;
import com.novabank.core.model.User;
import com.novabank.core.repository.UserRepository;
import com.novabank.core.service.AccountService;
import com.novabank.core.service.TransactionService;
import com.novabank.core.service.UserService;
import com.novabank.core.dto.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb2;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TransactionEdgeTests {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountService accountService;
    @Autowired
    private TransactionService transactionService;

    private User bootstrapUser() {
        String unique = "bob_" + System.nanoTime();
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername(unique);
        rr.setEmail(unique + "@example.com");
        rr.setPassword("password123");
        rr.setRole(Role.CUSTOMER);
        userService.register(rr);
        return userRepository.findByUsername(unique).orElseThrow();
    }

    @Test
    void transferFailsOnNegativeAmount() {
        User user = bootstrapUser();
        var a1 = accountService.createAccount(user);
        var a2 = accountService.createAccount(user);

        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(a1.getAccountNumber());
        tr.setToAccount(a2.getAccountNumber());
        tr.setAmount(new BigDecimal("-10.00"));

        assertThatThrownBy(() -> transactionService.transfer(user, tr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void transferFailsOnInsufficientFunds() {
        User user = bootstrapUser();
        var a1 = accountService.createAccount(user);
        var a2 = accountService.createAccount(user);

        TransferRequest tr = new TransferRequest();
        tr.setFromAccount(a1.getAccountNumber());
        tr.setToAccount(a2.getAccountNumber());
        tr.setAmount(new BigDecimal("100.00"));

        assertThatThrownBy(() -> transactionService.transfer(user, tr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient");
    }
}
