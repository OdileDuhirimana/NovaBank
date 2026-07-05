package com.novabank.core.service;

import com.novabank.core.common.SortSupport;
import com.novabank.core.dto.admin.AdminAccountResponse;
import com.novabank.core.dto.admin.AuditLogResponse;
import com.novabank.core.dto.admin.FraudLogResponse;
import com.novabank.core.model.Account;
import com.novabank.core.model.AuditLog;
import com.novabank.core.model.FraudLog;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.AuditLogRepository;
import com.novabank.core.repository.FraudLogRepository;
import com.novabank.core.repository.spec.AuditLogSpecifications;
import com.novabank.core.repository.spec.FraudLogSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Backing service for {@code AdminController}.
 *
 * Why this class exists: {@code AdminController} previously injected {@code AccountRepository},
 * {@code AuditLogRepository}, and {@code FraudLogRepository} directly, bypassing the service
 * layer entirely — a real violation of the controller -> service -> repository dependency
 * direction the project's own README and architecture diagram claim (and which
 * {@code ArchitectureFitnessTests} now mechanically enforces). Centralizing admin read access
 * here also gives audit-log-read logging, DTO mapping, and filter/sort handling exactly one
 * home instead of duplicating them across a controller method for each listing endpoint.
 *
 * Also closes SEC-08 ("no audit trail for who *reads* sensitive admin data"): every audit-log
 * and fraud-log listing call is itself recorded as an audit event, naming the reading admin —
 * compliance visibility for reads, not just writes, is the entire point of a system that
 * markets itself on auditability.
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final Set<String> ACCOUNT_SORT_FIELDS = Set.of("accountNumber", "balance", "createdAt");
    private static final Set<String> AUDIT_SORT_FIELDS = Set.of("createdAt", "actor", "action");
    private static final Set<String> FRAUD_SORT_FIELDS = Set.of("createdAt", "username", "eventType");

    private final AccountRepository accountRepository;
    private final AuditLogRepository auditLogRepository;
    private final FraudLogRepository fraudLogRepository;
    private final AuditService auditService;

    public Page<AdminAccountResponse> listAccounts(String readingAdmin, Boolean active, String username, int page, int size, String sort) {
        Sort dbSort = SortSupport.buildSort(sort, ACCOUNT_SORT_FIELDS);
        Pageable pageable = dbSort == null ? PageRequest.of(page, size) : PageRequest.of(page, size, dbSort);

        boolean hasUsername = username != null && !username.isBlank();
        Page<Account> result;
        if (active != null && hasUsername) {
            result = accountRepository.findByActiveAndUser_UsernameContainingIgnoreCase(active, username, pageable);
        } else if (active != null) {
            result = accountRepository.findByActive(active, pageable);
        } else if (hasUsername) {
            result = accountRepository.findByUser_UsernameContainingIgnoreCase(username, pageable);
        } else {
            result = accountRepository.findAll(pageable);
        }

        Page<AdminAccountResponse> mapped = result.map(a -> new AdminAccountResponse(
                a.getAccountNumber(),
                a.getBalance(),
                a.isActive(),
                a.getUser().getUsername(),
                a.getCreatedAt()
        ));

        auditService.log(readingAdmin, "ADMIN_ACCOUNT_LIST_READ", null, null,
                "Viewed account listing (page=" + page + ", size=" + size
                        + (active != null ? ", active=" + active : "")
                        + (hasUsername ? ", username=" + username : "") + ")");
        return mapped;
    }

    public Page<AuditLogResponse> listAuditLogs(String readingAdmin, String actor, String action, int page, int size, String sort) {
        Sort dbSort = SortSupport.buildSort(sort, AUDIT_SORT_FIELDS);
        Pageable pageable = dbSort == null ? PageRequest.of(page, size) : PageRequest.of(page, size, dbSort);

        Specification<AuditLog> spec = Specification.where(null);
        if (actor != null && !actor.isBlank()) {
            spec = spec.and(AuditLogSpecifications.actorContains(actor));
        }
        if (action != null && !action.isBlank()) {
            spec = spec.and(AuditLogSpecifications.actionEquals(action));
        }

        Page<AuditLogResponse> results = auditLogRepository.findAll(spec, pageable)
                .map(log -> new AuditLogResponse(
                        log.getId(), log.getActor(), log.getAction(), log.getAccountNumber(),
                        log.getReference(), log.getDetails(), log.getCreatedAt()
                ));

        auditService.log(readingAdmin, "ADMIN_AUDIT_LOG_READ", null, null,
                "Viewed audit logs (page=" + page + ", size=" + size
                        + (actor != null ? ", actor=" + actor : "")
                        + (action != null ? ", action=" + action : "") + ")");
        return results;
    }

    public Page<FraudLogResponse> listFraudLogs(String readingAdmin, String username, String eventType, int page, int size, String sort) {
        Sort dbSort = SortSupport.buildSort(sort, FRAUD_SORT_FIELDS);
        Pageable pageable = dbSort == null ? PageRequest.of(page, size) : PageRequest.of(page, size, dbSort);

        Specification<FraudLog> spec = Specification.where(null);
        if (username != null && !username.isBlank()) {
            spec = spec.and(FraudLogSpecifications.usernameContains(username));
        }
        if (eventType != null && !eventType.isBlank()) {
            spec = spec.and(FraudLogSpecifications.eventTypeEquals(eventType));
        }

        Page<FraudLogResponse> results = fraudLogRepository.findAll(spec, pageable)
                .map(log -> new FraudLogResponse(
                        log.getId(), log.getEventType(), log.getUsername(), log.getAccountNumber(),
                        log.getDetails(), log.isFlagged(), log.getCreatedAt()
                ));

        auditService.log(readingAdmin, "ADMIN_FRAUD_LOG_READ", null, null,
                "Viewed fraud logs (page=" + page + ", size=" + size
                        + (username != null ? ", username=" + username : "")
                        + (eventType != null ? ", eventType=" + eventType : "") + ")");
        return results;
    }
}
