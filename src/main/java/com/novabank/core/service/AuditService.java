package com.novabank.core.service;

import com.novabank.core.model.AuditLog;
import com.novabank.core.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository auditLogRepository;

    public void log(String actor, String action, String accountNumber, String reference, String details) {
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setAction(action);
        log.setAccountNumber(accountNumber);
        log.setReference(reference);
        log.setDetails(details);
        auditLogRepository.save(log);
    }
}
