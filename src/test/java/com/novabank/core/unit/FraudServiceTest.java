package com.novabank.core.unit;

import com.novabank.core.model.FraudLog;
import com.novabank.core.repository.FraudLogRepository;
import com.novabank.core.service.FraudService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Isolated unit tests for {@link FraudService} — no Spring context, no database. The
 * collaborator ({@link FraudLogRepository}) is mocked with Mockito so these tests exercise only
 * FraudService's own decision logic (the $10,000 large-transaction threshold) in isolation,
 * addressing the code review finding that the test suite had no true unit tests (isolated,
 * mocked-dependency tests) for any service class — every existing test was an integration-style
 * @SpringBootTest against a real (H2) database.
 */
@ExtendWith(MockitoExtension.class)
class FraudServiceTest {

    @Mock
    private FraudLogRepository fraudLogRepository;

    private FraudService fraudService;

    @BeforeEach
    void setUp() {
        fraudService = new FraudService(fraudLogRepository);
    }

    @Test
    void flagsTransactionAtExactlyTheThreshold() {
        boolean flagged = fraudService.checkAndLogLargeTransaction(
                "alice", "1111-2222-3333", new BigDecimal("10000.00"), "LARGE_DEPOSIT");

        assertThat(flagged).isTrue();
        ArgumentCaptor<FraudLog> captor = ArgumentCaptor.forClass(FraudLog.class);
        verify(fraudLogRepository).save(captor.capture());
        FraudLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("LARGE_DEPOSIT");
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getAccountNumber()).isEqualTo("1111-2222-3333");
        assertThat(saved.isFlagged()).isTrue();
        assertThat(saved.getDetails()).contains("10000.00");
    }

    @Test
    void flagsTransactionAboveTheThreshold() {
        boolean flagged = fraudService.checkAndLogLargeTransaction(
                "bob", "4444-5555-6666", new BigDecimal("25000.50"), "LARGE_TRANSFER");

        assertThat(flagged).isTrue();
        verify(fraudLogRepository).save(any(FraudLog.class));
    }

    @Test
    void doesNotFlagTransactionBelowTheThreshold() {
        boolean flagged = fraudService.checkAndLogLargeTransaction(
                "carol", "7777-8888-9999", new BigDecimal("9999.99"), "LARGE_WITHDRAWAL");

        assertThat(flagged).isFalse();
        verify(fraudLogRepository, never()).save(any(FraudLog.class));
    }

    @Test
    void logsFailedLoginAttemptUnconditionally() {
        fraudService.logFailedLogin("mallory");

        ArgumentCaptor<FraudLog> captor = ArgumentCaptor.forClass(FraudLog.class);
        verify(fraudLogRepository).save(captor.capture());
        FraudLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("FAILED_LOGIN");
        assertThat(saved.getUsername()).isEqualTo("mallory");
        assertThat(saved.isFlagged()).isTrue();
    }
}
