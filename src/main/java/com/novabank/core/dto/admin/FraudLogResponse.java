package com.novabank.core.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Response shape for {@code GET /api/v1/admin/fraud}, replacing a direct {@code FraudLog} JPA
 * entity in the response body — see {@link AuditLogResponse} for the same rationale.
 */
@Data
@AllArgsConstructor
public class FraudLogResponse {
    private Long id;
    private String eventType;
    private String username;
    private String accountNumber;
    private String details;
    private boolean flagged;
    private Instant createdAt;
}
