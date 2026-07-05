package com.novabank.core.unit;

import com.novabank.core.dto.account.AccountResponse;
import com.novabank.core.model.Account;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import com.novabank.core.service.AccountService;
import com.novabank.core.service.AuditService;
import com.novabank.core.service.FraudService;
import com.novabank.core.service.WebhookOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated (Mockito, no Spring context, no database) unit test for {@link AccountService},
 * complementing the existing MockMvc/H2 integration coverage
 * ({@code AccountTransactionControllerTests}, {@code AdminAccountManagementTests}) with fast,
 * fully isolated coverage of every ownership/state-invariant branch.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRecordRepository txRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private FraudService fraudService;
    @Mock
    private WebhookOutboxService webhookOutboxService;

    private AccountService service;
    private User owner;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new AccountService(accountRepository, txRepository, auditService, fraudService, webhookOutboxService);

        owner = new User();
        owner.setUsername("alice");
        ReflectionTestUtils.setField(owner, "id", 1L);

        account = new Account();
        account.setUser(owner);
        account.setAccountNumber("1111-1111-1111");
        account.setBalance(new BigDecimal("100.00"));
        account.setActive(true);
    }

    @Test
    void createAccountGeneratesAUniqueNumberAndStartsAtZeroBalance() {
        lenient().when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);

        AccountResponse response = service.createAccount(owner);

        assertThat(response.getAccountNumber()).matches("\\d{4}-\\d{4}-\\d{4}");
        assertThat(response.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.isActive()).isTrue();
        verify(auditService).log(eq("alice"), eq("ACCOUNT_CREATE"), anyString(), eq(null), anyString());
    }

    @Test
    void depositRejectsACallerWhoDoesNotOwnTheAccount() {
        User attacker = new User();
        attacker.setUsername("mallory");
        ReflectionTestUtils.setField(attacker, "id", 2L);
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.deposit(attacker, "1111-1111-1111", new BigDecimal("10"), "note"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void depositRejectsAnInactiveAccount() {
        account.setActive(false);
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.deposit(owner, "1111-1111-1111", new BigDecimal("10"), "note"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void depositIncreasesBalanceAndLogsAuditAndFraudCheck() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(account));

        AccountResponse response = service.deposit(owner, "1111-1111-1111", new BigDecimal("50.00"), "salary");

        assertThat(response.getBalance()).isEqualByComparingTo("150.00");
        verify(auditService).log(eq("alice"), eq("DEPOSIT"), eq("1111-1111-1111"), anyString(), anyString());
        verify(fraudService).checkAndLogLargeTransaction(eq("alice"), eq("1111-1111-1111"), eq(new BigDecimal("50.00")), eq("LARGE_DEPOSIT"));
    }

    @Test
    void withdrawRejectsInsufficientFunds() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.withdraw(owner, "1111-1111-1111", new BigDecimal("500.00"), "note"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void withdrawDecreasesBalance() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(account));

        AccountResponse response = service.withdraw(owner, "1111-1111-1111", new BigDecimal("40.00"), "ATM");

        assertThat(response.getBalance()).isEqualByComparingTo("60.00");
        verify(fraudService).checkAndLogLargeTransaction(eq("alice"), eq("1111-1111-1111"), eq(new BigDecimal("40.00")), eq("LARGE_WITHDRAWAL"));
    }

    @Test
    void freezingAnAccountEnqueuesAnAccountFrozenWebhookEvent() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(account));

        service.updateAccountStatus(owner, "1111-1111-1111", false, "suspicious activity");

        verify(webhookOutboxService).enqueue(eq("ACCOUNT_FROZEN"), anyMap());
        verify(auditService).log(eq("alice"), eq("ACCOUNT_FREEZE"), eq("1111-1111-1111"), eq(null), anyString());
    }

    @Test
    void reactivatingAnAccountDoesNotEnqueueAWebhookEvent() {
        account.setActive(false);
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(account));

        service.updateAccountStatus(owner, "1111-1111-1111", true, null);

        verify(webhookOutboxService, never()).enqueue(anyString(), anyMap());
        verify(auditService).log(eq("alice"), eq("ACCOUNT_ACTIVATE"), eq("1111-1111-1111"), eq(null), anyString());
    }
}
