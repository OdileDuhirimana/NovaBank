package com.novabank.core.common;

import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Translates the API's {@code sort=field,dir} query convention (e.g. {@code "amount,desc"}) into
 * a JPA {@link Sort}, so ordering happens as part of the SQL query (ORDER BY) rather than an
 * in-memory {@code Comparator} applied after loading rows — and so an attacker cannot inject an
 * arbitrary entity property (or SQL-adjacent expression) into {@code ORDER BY} via the
 * {@code sort} query parameter, since only an explicit per-endpoint allow-list of field names is
 * ever accepted.
 *
 * Extracted from {@code TransactionService} (which had this exact logic private and
 * un-reusable) so {@code AdminService}'s account/audit-log/fraud-log listings can apply the same
 * safe, consistent sorting contract instead of admin endpoints having no sort support at all —
 * closing the "sorting only implemented on the transaction endpoints" gap.
 */
public final class SortSupport {

    private SortSupport() {
    }

    /**
     * @param sort           raw {@code sort} query parameter, e.g. {@code "createdAt,desc"}; a
     *                       blank/null value means "no explicit sort, defer to caller's default".
     * @param allowedFields  the exact set of entity property names this endpoint permits sorting
     *                       on.
     * @return the resolved {@link Sort}, or {@code null} if {@code sort} was blank.
     * @throws IllegalArgumentException if {@code sort} names a field outside {@code allowedFields}.
     */
    public static Sort buildSort(String sort, Set<String> allowedFields) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        String dir = parts.length > 1 ? parts[1].trim().toLowerCase() : "asc";
        if (!allowedFields.contains(field)) {
            throw new IllegalArgumentException("Invalid sort field. Allowed: " + String.join(", ", allowedFields));
        }
        Sort.Direction direction = "desc".equals(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, field);
    }
}
