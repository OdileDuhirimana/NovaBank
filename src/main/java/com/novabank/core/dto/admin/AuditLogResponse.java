package com.novabank.core.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Response shape for {@code GET /api/v1/admin/audit}, replacing a direct {@code AuditLog} JPA
 * entity in the response body. The controller previously returned {@code Page<AuditLog>}
 * directly, contradicting the project's own stated "never expose JPA entities over the wire"
 * principle — any future field added to the entity (or a lazy association, had one existed)
 * would have been automatically serialized to every ADMIN/AUDITOR caller with no review gate.
 */
@Data
@AllArgsConstructor
public class AuditLogResponse {
    private Long id;
    private String actor;
    private String action;
    private String accountNumber;
    private String reference;
    private String details;
    private Instant createdAt;
}
