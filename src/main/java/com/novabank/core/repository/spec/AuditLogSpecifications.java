package com.novabank.core.repository.spec;

import com.novabank.core.model.AuditLog;
import org.springframework.data.jpa.domain.Specification;

/**
 * Database-level filtering for {@code GET /api/v1/admin/audit}, mirroring the
 * {@link TransactionSpecifications} pattern already established for transaction listing — closes
 * the "admin audit-log endpoint has no filtering support at all" gap by pushing filters into the
 * SQL WHERE clause instead of requiring the caller to page through and discard unrelated rows.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> actorContains(String actor) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("actor")), "%" + actor.toLowerCase() + "%");
    }

    public static Specification<AuditLog> actionEquals(String action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }
}
