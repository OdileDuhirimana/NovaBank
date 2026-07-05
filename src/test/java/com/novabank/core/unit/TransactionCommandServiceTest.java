package com.novabank.core.unit;

import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.Account;
import com.novabank.core.model.TransferIdempotencyRecord;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import com.novabank.core.repository.TransferIdempotencyRecordRepository;
import com.novabank.core.service.AuditService;
import com.novabank.core.service.FraudService;
import com.novabank.core.service.TransactionCommandService;
import com.novabank.core.service.WebhookOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated (Mockito, no Spring context, no database) unit test for
 * {@link TransactionCommandService} — the write path split out of the former monolithic
 * {@code TransactionService}. Complements the existing MockMvc/H2-backed integration tests
 * ({@code TransactionEdgeTests}, {@code TransferIdempotencyControllerTests},
 * {@code ConcurrentTransferTests}) with fast, fully isolated coverage of every branch in
 * {@code performTransfer}/{@code transfer}, addressing the code review's call to raise coverage
 * specifically on the transaction/account services.
 */
@ExtendWith(MockitoExtension.class)
class TransactionCommandServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionRecordRepository txRepository;
    @Mock
    private TransferIdempotencyRecordRepository idempotencyRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private FraudService fraudService;
    @Mock
    private WebhookOutboxService webhookOutboxService;

    private TransactionCommandService service;
    private User owner;
    private Account fromAccount;
    private Account toAccount;

    @BeforeEach
    void setUp() {
        service = new TransactionCommandService(accountRepository, txRepository, idempotencyRepository,
                auditService, fraudService, webhookOutboxService);

        owner = new User();
        owner.setUsername("alice");
        ReflectionTestUtils.setField(owner, "id", 1L);

        fromAccount = new Account();
        fromAccount.setUser(owner);
        fromAccount.setAccountNumber("1111-1111-1111");
        fromAccount.setBalance(new BigDecimal("500.00"));
        fromAccount.setActive(true);

        toAccount = new Account();
        User otherOwner = new User();
        otherOwner.setUsername("bob");
        ReflectionTestUtils.setField(otherOwner, "id", 2L);
        toAccount.setUser(otherOwner);
        toAccount.setAccountNumber("2222-2222-2222");
        toAccount.setBalance(BigDecimal.ZERO);
        toAccount.setActive(true);
    }

    private TransferRequest request(String from, String to, String amount) {
        TransferRequest request = new TransferRequest();
        request.setFromAccount(from);
        request.setToAccount(to);
        request.setAmount(new BigDecimal(amount));
        request.setNote("test");
        return request;
    }

    @Test
    void rejectsTransferToTheSameAccount() {
        TransferRequest request = request("1111-1111-1111", "1111-1111-1111", "10.00");

        assertThatThrownBy(() -> service.transfer(owner, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");
        verify(accountRepository, never()).findByAccountNumber(anyString());
    }

    @Test
    void rejectsWhenCallerDoesNotOwnTheSourceAccount() {
        User attacker = new User();
        attacker.setUsername("mallory");
        ReflectionTestUtils.setField(attacker, "id", 99L);

        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));

        TransferRequest request = request("1111-1111-1111", "2222-2222-2222", "10.00");

        assertThatThrownBy(() -> service.transfer(attacker, request))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsTransferFromAnInactiveSourceAccount() {
        fromAccount.setActive(false);
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> service.transfer(owner, request("1111-1111-1111", "2222-2222-2222", "10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void rejectsTransferToAnInactiveDestinationAccount() {
        toAccount.setActive(false);
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> service.transfer(owner, request("1111-1111-1111", "2222-2222-2222", "10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination account is inactive");
    }

    @Test
    void rejectsInsufficientFunds() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> service.transfer(owner, request("1111-1111-1111", "2222-2222-2222", "999999.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void successfulTransferDebitsCreditsAndLogsAudit() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));
        when(fraudService.checkAndLogLargeTransaction(any(), any(), any(), any())).thenReturn(false);

        String reference = service.transfer(owner, request("1111-1111-1111", "2222-2222-2222", "100.00"));

        assertThat(reference).isNotBlank();
        assertThat(fromAccount.getBalance()).isEqualByComparingTo("400.00");
        assertThat(toAccount.getBalance()).isEqualByComparingTo("100.00");
        verify(auditService).log(eq("alice"), eq("TRANSFER"), eq("1111-1111-1111"), anyString(), anyString());
        verify(webhookOutboxService, never()).enqueue(anyString(), anyMap());
    }

    @Test
    void aFlaggedLargeTransferEnqueuesAWebhookOutboxEvent() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));
        fromAccount.setBalance(new BigDecimal("20000.00"));
        when(fraudService.checkAndLogLargeTransaction(any(), any(), any(), any())).thenReturn(true);

        service.transfer(owner, request("1111-1111-1111", "2222-2222-2222", "15000.00"));

        verify(webhookOutboxService).enqueue(eq("LARGE_TRANSFER"), anyMap());
    }

    @Test
    void idempotentRetryWithTheSameKeyReturnsTheSameReferenceWithoutTransferringTwice() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));
        when(fraudService.checkAndLogLargeTransaction(any(), any(), any(), any())).thenReturn(false);
        when(idempotencyRepository.findByActorUsernameAndIdempotencyKey(eq("alice"), eq("key-1")))
                .thenReturn(Optional.empty());

        TransferRequest request = request("1111-1111-1111", "2222-2222-2222", "50.00");
        String firstReference = service.transfer(owner, request, "key-1");

        assertThat(fromAccount.getBalance()).isEqualByComparingTo("450.00");
        verify(idempotencyRepository).save(any(TransferIdempotencyRecord.class));

        // Simulate the retry: the record now "exists" in the repository.
        TransferIdempotencyRecord existing = new TransferIdempotencyRecord();
        existing.setActorUsername("alice");
        existing.setIdempotencyKey("key-1");
        existing.setTransferReference(firstReference);
        existing.setRequestHash((String) ReflectionTestUtils.invokeMethod(
                service, "hashTransferRequest", request));
        when(idempotencyRepository.findByActorUsernameAndIdempotencyKey(eq("alice"), eq("key-1")))
                .thenReturn(Optional.of(existing));

        String secondReference = service.transfer(owner, request, "key-1");

        assertThat(secondReference).isEqualTo(firstReference);
        // Balance must not have moved a second time.
        assertThat(fromAccount.getBalance()).isEqualByComparingTo("450.00");
    }

    @Test
    void reusingAnIdempotencyKeyWithADifferentPayloadIsRejected() {
        TransferIdempotencyRecord existing = new TransferIdempotencyRecord();
        existing.setActorUsername("alice");
        existing.setIdempotencyKey("key-1");
        existing.setRequestHash("does-not-match-anything");
        existing.setTransferReference("some-ref");
        when(idempotencyRepository.findByActorUsernameAndIdempotencyKey(eq("alice"), eq("key-1")))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transfer(owner, request("1111-1111-1111", "2222-2222-2222", "50.00"), "key-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different transfer payload");
    }

    @Test
    void aRaceOnTheIdempotencyKeyReturnsTheWinningRequestsReference() {
        when(accountRepository.findByAccountNumber("1111-1111-1111")).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByAccountNumber("2222-2222-2222")).thenReturn(Optional.of(toAccount));
        when(fraudService.checkAndLogLargeTransaction(any(), any(), any(), any())).thenReturn(false);

        TransferRequest request = request("1111-1111-1111", "2222-2222-2222", "50.00");
        when(idempotencyRepository.save(any(TransferIdempotencyRecord.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violated"));

        TransferIdempotencyRecord raceWinner = new TransferIdempotencyRecord();
        raceWinner.setActorUsername("alice");
        raceWinner.setIdempotencyKey("key-1");
        raceWinner.setTransferReference("winning-reference");
        raceWinner.setRequestHash((String) ReflectionTestUtils.invokeMethod(service, "hashTransferRequest", request));

        // First lookup (before the race): empty. Second lookup (after losing the race): the winner.
        when(idempotencyRepository.findByActorUsernameAndIdempotencyKey(eq("alice"), eq("key-1")))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raceWinner));

        String reference = service.transfer(owner, request, "key-1");

        assertThat(reference).isEqualTo("winning-reference");
    }

    @Test
    void rejectsAnIdempotencyKeyLongerThan100Characters() {
        String tooLong = "k".repeat(101);
        assertThatThrownBy(() -> service.transfer(owner, request("1111-1111-1111", "2222-2222-2222", "10.00"), tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100 characters");
    }
}
