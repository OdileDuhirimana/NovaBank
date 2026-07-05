package com.novabank.core;

import com.novabank.core.dto.auth.RegisterRequest;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.UserRepository;
import com.novabank.core.service.AccountService;
import com.novabank.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the money-correctness defect found in the code review: concurrent
 * read-modify-write updates to Account.balance (deposit/withdraw/transfer all do
 * balance = balance.add/subtract(amount) then save) had no concurrency control, so two
 * simultaneous requests against the same account could race, both read the same starting
 * balance, and one write could silently clobber the other ("lost update") — an actual
 * money-correctness bug in a banking system.
 *
 * Account.balance is now guarded by @Version optimistic locking. Concurrent writers to the
 * same row will have exactly one "loser" per conflicting pair rejected with
 * ObjectOptimisticLockingFailureException instead of silently losing an update. This test
 * fires many concurrent deposits at a single shared account with a retry-on-conflict loop
 * (the standard, expected caller-side pattern for optimistic locking) and asserts the final
 * balance exactly equals the sum of every deposit that reported success — i.e. no update is
 * ever silently lost, whether or not a retry was needed.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testconcurrent;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ConcurrentTransferTests {

    private static final int CONCURRENT_REQUESTS = 20;
    private static final BigDecimal DEPOSIT_AMOUNT = new BigDecimal("10.00");
    private static final int MAX_RETRIES_PER_REQUEST = 50;

    @Autowired
    private UserService userService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private UserRepository userRepository;

    private User bootstrapUser() {
        String unique = "concurrent_" + System.nanoTime();
        RegisterRequest rr = new RegisterRequest();
        rr.setUsername(unique);
        rr.setEmail(unique + "@example.com");
        rr.setPassword("password123");
        userService.register(rr);
        return userRepository.findByUsername(unique).orElseThrow();
    }

    @Test
    void concurrentDepositsToSameAccountProduceNoLostUpdates() throws Exception {
        User user = bootstrapUser();
        var account = accountService.createAccount(user);
        String accountNumber = account.getAccountNumber();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        AtomicInteger successCount = new AtomicInteger(0);

        try {
            List<Callable<Void>> tasks = IntStream.range(0, CONCURRENT_REQUESTS)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        depositWithRetry(user, accountNumber, DEPOSIT_AMOUNT);
                        successCount.incrementAndGet();
                        return null;
                    })
                    .collect(Collectors.toList());

            List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                f.get(); // propagate any unexpected exception (fails the test loudly)
            }
        } finally {
            executor.shutdown();
        }

        var finalAccount = accountRepository.findByAccountNumber(accountNumber).orElseThrow();
        BigDecimal expectedBalance = DEPOSIT_AMOUNT.multiply(BigDecimal.valueOf(successCount.get()));

        assertThat(successCount.get()).isEqualTo(CONCURRENT_REQUESTS);
        assertThat(finalAccount.getBalance()).isEqualByComparingTo(expectedBalance);
    }

    /**
     * Retries a deposit on optimistic-lock conflict, mirroring the pattern a well-behaved API
     * client is expected to follow when it receives the 409 CONCURRENT_UPDATE_CONFLICT response
     * mapped in GlobalExceptionHandler. This is the mechanism that proves no update is lost: a
     * conflicting writer never silently overwrites another's change, it is rejected and retried
     * against the fresh row state.
     */
    private void depositWithRetry(User user, String accountNumber, BigDecimal amount) {
        int attempts = 0;
        while (true) {
            try {
                accountService.deposit(user, accountNumber, amount, "concurrent-test");
                return;
            } catch (OptimisticLockingFailureException ex) {
                attempts++;
                if (attempts >= MAX_RETRIES_PER_REQUEST) {
                    throw ex;
                }
                // Small backoff to reduce the chance of repeatedly colliding with the same set
                // of competing threads under heavy contention on a single row.
                try {
                    Thread.sleep(5L + (long) (Math.random() * 15));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }
}
