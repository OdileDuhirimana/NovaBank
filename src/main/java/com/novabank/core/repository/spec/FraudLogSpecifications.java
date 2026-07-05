package com.novabank.core.repository.spec;

import com.novabank.core.model.FraudLog;
import org.springframework.data.jpa.domain.Specification;

/**
 * Database-level filtering for {@code GET /api/v1/admin/fraud} — see
 * {@link AuditLogSpecifications} for the same rationale applied to audit logs.
 */
public final class FraudLogSpecifications {

    private FraudLogSpecifications() {
    }

    public static Specification<FraudLog> usernameContains(String username) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("username")), "%" + username.toLowerCase() + "%");
    }

    public static Specification<FraudLog> eventTypeEquals(String eventType) {
        return (root, query, cb) -> cb.equal(root.get("eventType"), eventType);
    }
}
