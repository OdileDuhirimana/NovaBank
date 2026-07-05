package com.novabank.core.repository.spec;

import com.novabank.core.model.Account;
import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.User;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Builds composable Spring Data JPA {@link Specification} predicates for transaction queries.
 *
 * Why this exists: the code review found that transaction listing, filtering, summarization,
 * and CSV export all loaded a user's <em>entire</em> transaction history into a Java
 * {@code List} via {@code findByFromAccount_UserOrToAccount_User(user, user)} and then applied
 * date/amount filtering, sorting, and pagination in application memory. That does not scale
 * past a trivial transaction volume and defeats the purpose of an indexed, paginated database
 * query. These specifications push every filter down into the generated SQL WHERE clause so
 * the database — not the JVM heap — does the filtering, and so a real {@link
 * org.springframework.data.domain.Pageable} can be used to page at the SQL level (LIMIT/OFFSET)
 * instead of loading everything and slicing a sub-list in Java.
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    /**
     * Transactions where the given user is the owner of the from-account or the to-account.
     *
     * Uses explicit LEFT JOINs rather than {@code root.get("fromAccount").get(...)} path
     * navigation: deposits have a null {@code fromAccount} and withdrawals have a null
     * {@code toAccount} (see TransactionRecord/AccountService), and an inner-join path
     * navigation would silently exclude every such row from an OR predicate — a correctness
     * bug that would make deposits/withdrawals disappear from a user's own transaction history.
     */
    public static Specification<TransactionRecord> belongsToUser(User user) {
        return (root, query, cb) -> {
            var fromAccountUser = root.join("fromAccount", JoinType.LEFT).join("user", JoinType.LEFT);
            var toAccountUser = root.join("toAccount", JoinType.LEFT).join("user", JoinType.LEFT);
            query.distinct(true);
            return cb.or(
                    cb.equal(fromAccountUser.get("id"), user.getId()),
                    cb.equal(toAccountUser.get("id"), user.getId())
            );
        };
    }

    /** Transactions where the given account is either side of the transaction (see note above
     *  on belongsToUser regarding why LEFT JOINs are required here too). */
    public static Specification<TransactionRecord> involvesAccount(Account account) {
        return (root, query, cb) -> {
            var fromAccount = root.join("fromAccount", JoinType.LEFT);
            var toAccount = root.join("toAccount", JoinType.LEFT);
            query.distinct(true);
            return cb.or(
                    cb.equal(fromAccount.get("id"), account.getId()),
                    cb.equal(toAccount.get("id"), account.getId())
            );
        };
    }

    public static Specification<TransactionRecord> occurredOnOrAfter(Instant start) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), start);
    }

    public static Specification<TransactionRecord> occurredBefore(Instant endExclusive) {
        return (root, query, cb) -> cb.lessThan(root.get("occurredAt"), endExclusive);
    }

    public static Specification<TransactionRecord> amountAtLeast(BigDecimal min) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("amount"), min);
    }

    public static Specification<TransactionRecord> amountAtMost(BigDecimal max) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("amount"), max);
    }
}
