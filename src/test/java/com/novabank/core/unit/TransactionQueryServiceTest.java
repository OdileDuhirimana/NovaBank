package com.novabank.core.unit;

import com.novabank.core.dto.transaction.TransactionSummaryResponse;
import com.novabank.core.model.Account;
import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import com.novabank.core.service.TransactionQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Isolated (Mockito, no Spring context) unit test for {@link TransactionQueryService} — the read
 * path split out of the former monolithic {@code TransactionService}. Targets the branches that
 * are awkward to exercise via the existing H2/MockMvc integration tests: invalid filter
 * combinations, sort-field validation, cashflow summary arithmetic, and CSV escaping.
 */
@ExtendWith(MockitoExtension.class)
class TransactionQueryServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRecordRepository txRepository;

    private TransactionQueryService service;
    private User user;
    private Account ownedAccount;

    @BeforeEach
    void setUp() {
        service = new TransactionQueryService(accountRepository, txRepository);
        user = new User();
        user.setUsername("alice");
        ReflectionTestUtils.setField(user, "id", 1L);

        ownedAccount = new Account();
        ownedAccount.setUser(user);
        ownedAccount.setAccountNumber("1111-1111-1111");
        ownedAccount.setActive(true);
    }

    @Test
    void rejectsAnInvertedAmountRange() {
        assertThatThrownBy(() -> service.listUserTransactionsFiltered(
                user, null, null, new BigDecimal("100"), new BigDecimal("10")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minAmount must be less than or equal to maxAmount");
    }

    @Test
    void rejectsAMalformedDate() {
        assertThatThrownBy(() -> service.listUserTransactionsFiltered(
                user, "not-a-date", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid date format");
    }

    @Test
    void rejectsAnInvalidSortFieldOnTheMyTransactionsEndpoint() {
        assertThatThrownBy(() -> service.listUserTransactionsWithOptions(
                user, null, null, null, null, null, null, "balance,desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort field");
    }

    @Test
    void listWithoutPageOrSizeReturnsEveryMatchingRowUnpaginated() {
        TransactionRecord tx = depositInto(ownedAccount, "100.00", Instant.now());
        when(txRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(tx));

        var results = service.listUserTransactionsWithOptions(
                user, null, null, null, null, null, null, "amount,asc");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listWithPageAndSizeDelegatesToThePagedRepositoryMethod() {
        TransactionRecord tx = depositInto(ownedAccount, "50.00", Instant.now());
        Page<TransactionRecord> page = new PageImpl<>(List.of(tx));
        when(txRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        var results = service.listUserTransactionsWithOptions(user, null, null, null, null, 0, 10, null);

        assertThat(results).hasSize(1);
    }

    @Test
    void summarizeComputesCreditsDebitsAndNetCashflowForTheScopedAccount() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(ownedAccount));

        Account otherAccount = new Account();
        User other = new User();
        other.setUsername("bob");
        ReflectionTestUtils.setField(other, "id", 2L);
        otherAccount.setUser(other);
        otherAccount.setAccountNumber("2222-2222-2222");
        otherAccount.setActive(true);

        TransactionRecord credit = transferBetween(otherAccount, ownedAccount, "300.00", Instant.now());
        TransactionRecord debit = transferBetween(ownedAccount, otherAccount, "120.00", Instant.now());
        when(txRepository.findAll(any(Specification.class))).thenReturn(List.of(credit, debit));

        TransactionSummaryResponse summary = service.summarizeUserTransactions(user, null, null, "1111-1111-1111");

        assertThat(summary.getTotalCredits()).isEqualByComparingTo("300.00");
        assertThat(summary.getTotalDebits()).isEqualByComparingTo("120.00");
        assertThat(summary.getNetCashflow()).isEqualByComparingTo("180.00");
        assertThat(summary.getTransactionCount()).isEqualTo(2);
    }

    @Test
    void summarizeRejectsAnAccountTheCallerDoesNotOwn() {
        Account someoneElsesAccount = new Account();
        User owner = new User();
        owner.setUsername("bob");
        ReflectionTestUtils.setField(owner, "id", 2L);
        someoneElsesAccount.setUser(owner);
        someoneElsesAccount.setAccountNumber("9999-9999-9999");
        when(accountRepository.findByAccountNumber("9999-9999-9999")).thenReturn(Optional.of(someoneElsesAccount));

        assertThatThrownBy(() -> service.summarizeUserTransactions(user, null, null, "9999-9999-9999"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void csvStatementEscapesEmbeddedQuotesAndWrapsEveryFieldInQuotes() {
        TransactionRecord tx = depositInto(ownedAccount, "10.00", Instant.now());
        tx.setNote("say \"hello\"");
        when(txRepository.findAll(any(Specification.class))).thenReturn(List.of(tx));

        String csv = service.buildStatementCsv(user, null, null, null, null, null);

        assertThat(csv).contains("\"say \"\"hello\"\"\"");
        assertThat(csv).startsWith("reference,type,amount,fromAccount,toAccount,occurredAt,note\n");
    }

    private TransactionRecord depositInto(Account account, String amount, Instant when) {
        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.DEPOSIT);
        tx.setToAccount(account);
        tx.setAmount(new BigDecimal(amount));
        tx.setOccurredAt(when.minus(1, ChronoUnit.SECONDS));
        return tx;
    }

    private TransactionRecord transferBetween(Account from, Account to, String amount, Instant when) {
        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.TRANSFER);
        tx.setFromAccount(from);
        tx.setToAccount(to);
        tx.setAmount(new BigDecimal(amount));
        tx.setOccurredAt(when);
        return tx;
    }
}
