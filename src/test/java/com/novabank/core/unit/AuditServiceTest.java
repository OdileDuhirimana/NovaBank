package com.novabank.core.unit;

import com.novabank.core.model.AuditLog;
import com.novabank.core.repository.AuditLogRepository;
import com.novabank.core.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Isolated unit test for {@link AuditService}. AuditLogRepository is mocked so this test
 * verifies AuditService correctly maps its parameters onto an AuditLog entity and persists it,
 * without needing a real (H2) database — the code review's "true unit test" gap this class
 * addresses.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogRepository);
    }

    @Test
    void logPersistsAllProvidedFieldsOnTheAuditEntity() {
        auditService.log("alice", "TRANSFER", "1111-2222-3333", "ref-abc-123", "Transfer to 4444-5555-6666 amount 100.00");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getActor()).isEqualTo("alice");
        assertThat(saved.getAction()).isEqualTo("TRANSFER");
        assertThat(saved.getAccountNumber()).isEqualTo("1111-2222-3333");
        assertThat(saved.getReference()).isEqualTo("ref-abc-123");
        assertThat(saved.getDetails()).isEqualTo("Transfer to 4444-5555-6666 amount 100.00");
    }

    @Test
    void logToleratesNullAccountNumberAndReference() {
        // REGISTER/LOGIN events have no associated account or transaction reference.
        auditService.log("bob", "LOGIN", null, null, "User logged in");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getActor()).isEqualTo("bob");
        assertThat(saved.getAction()).isEqualTo("LOGIN");
        assertThat(saved.getAccountNumber()).isNull();
        assertThat(saved.getReference()).isNull();
        assertThat(saved.getDetails()).isEqualTo("User logged in");
    }
}
