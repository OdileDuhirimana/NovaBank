package com.novabank.core.service;

import com.novabank.core.common.PaginationDefaults;
import com.novabank.core.common.SortSupport;
import com.novabank.core.dto.transaction.TransactionResponse;
import com.novabank.core.dto.transaction.TransactionSummaryResponse;
import com.novabank.core.model.Account;
import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import com.novabank.core.repository.spec.TransactionSpecifications;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Owns every read path over transaction history: listing, filtering, sorting, pagination,
 * cashflow summarization, and CSV statement export.
 *
 * Split out of the former monolithic {@code TransactionService} — see
 * {@link TransactionCommandService} Javadoc for the full rationale. This class has no write
 * methods and no dependency on webhook/fraud/idempotency collaborators, since none of those are
 * relevant to answering "what happened."
 */
@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("occurredAt", "amount", "type");

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository txRepository;

    @Transactional
    public List<TransactionResponse> listUserTransactions(User user) {
        return txRepository.findByFromAccount_UserOrToAccount_User(user, user)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Filters a user's transactions by optional date range and amount range, entirely at the
     * database layer via a {@link Specification} (translated to a SQL WHERE clause), rather
     * than loading the user's full transaction history into a Java {@code List} and filtering
     * with stream predicates.
     */
    @Transactional
    public List<TransactionResponse> listUserTransactionsFiltered(
            User user,
            String startDate,
            String endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        Specification<TransactionRecord> spec = buildFilterSpecification(user, startDate, endDate, minAmount, maxAmount);
        return txRepository.findAll(spec)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Lists a user's transactions with optional filtering, sorting, and pagination — all pushed
     * down to the database. Sorting is translated into a JPA {@link Sort} (SQL ORDER BY) and
     * pagination into a {@link Pageable} (SQL LIMIT/OFFSET via Spring Data), so only the
     * requested page of rows is ever loaded into memory, regardless of how large the user's full
     * transaction history is.
     *
     * The response shape is intentionally kept as a plain {@code List<TransactionResponse>}
     * (rather than a Spring Data {@code Page<>} envelope) to preserve the existing API contract
     * for {@code GET /api/v1/transactions/my} — only the internal query strategy changed.
     */
    @Transactional
    public List<TransactionResponse> listUserTransactionsWithOptions(
            User user,
            String startDate,
            String endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer page,
            Integer size,
            String sort
    ) {
        Specification<TransactionRecord> spec = buildFilterSpecification(user, startDate, endDate, minAmount, maxAmount);
        Sort dbSort = SortSupport.buildSort(sort, ALLOWED_SORT_FIELDS);

        if (page == null && size == null) {
            List<TransactionRecord> results = dbSort == null
                    ? txRepository.findAll(spec)
                    : txRepository.findAll(spec, dbSort);
            return results.stream().map(this::toResponse).collect(Collectors.toList());
        }

        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? PaginationDefaults.DEFAULT_PAGE_SIZE : Math.max(1, size);
        Pageable pageable = dbSort == null ? PageRequest.of(p, s) : PageRequest.of(p, s, dbSort);
        return txRepository.findAll(spec, pageable)
                .map(this::toResponse)
                .getContent();
    }

    @Transactional
    public TransactionSummaryResponse summarizeUserTransactions(User user, String startDate, String endDate, String accountNumber) {
        String scopedAccount = (accountNumber == null || accountNumber.isBlank()) ? null : accountNumber;
        // resolveScopeAccounts performs the ownership check (throws SecurityException if the
        // caller does not own the requested account) and returns the account number(s) the
        // summary should be scoped to — reused below both for in-memory credit/debit
        // attribution and to narrow the DB-level query to just those accounts' transactions.
        Set<String> scopeAccounts = resolveScopeAccounts(user, accountNumber);

        Specification<TransactionRecord> spec = buildFilterSpecification(user, startDate, endDate, null, null);
        if (scopedAccount != null) {
            Account account = accountRepository.findByAccountNumber(scopedAccount)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));
            spec = spec.and(TransactionSpecifications.involvesAccount(account));
        }
        List<TransactionResponse> transactions = txRepository.findAll(spec)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal largestCredit = BigDecimal.ZERO;
        BigDecimal largestDebit = BigDecimal.ZERO;
        long internalTransferCount = 0;
        Map<String, BigDecimal> monthlyNet = new TreeMap<>();

        for (TransactionResponse tx : transactions) {
            boolean fromInScope = tx.getFromAccount() != null && scopeAccounts.contains(tx.getFromAccount());
            boolean toInScope = tx.getToAccount() != null && scopeAccounts.contains(tx.getToAccount());
            BigDecimal amount = tx.getAmount();

            if (toInScope && !fromInScope) {
                totalCredits = totalCredits.add(amount);
                if (amount.compareTo(largestCredit) > 0) {
                    largestCredit = amount;
                }
                accumulateMonthly(monthlyNet, tx.getOccurredAt(), amount);
            } else if (fromInScope && !toInScope) {
                totalDebits = totalDebits.add(amount);
                if (amount.compareTo(largestDebit) > 0) {
                    largestDebit = amount;
                }
                accumulateMonthly(monthlyNet, tx.getOccurredAt(), amount.negate());
            } else if (fromInScope) {
                internalTransferCount++;
            }
        }

        return TransactionSummaryResponse.builder()
                .scopeAccountNumber(scopedAccount)
                .startDate(startDate)
                .endDate(endDate)
                .transactionCount(transactions.size())
                .internalTransferCount(internalTransferCount)
                .totalCredits(totalCredits)
                .totalDebits(totalDebits)
                .netCashflow(totalCredits.subtract(totalDebits))
                .largestCredit(largestCredit)
                .largestDebit(largestDebit)
                .monthlyNetCashflow(monthlyNet)
                .build();
    }

    @Transactional
    public String buildStatementCsv(
            User user,
            String startDate,
            String endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String sort
    ) {
        Specification<TransactionRecord> spec = buildFilterSpecification(user, startDate, endDate, minAmount, maxAmount);
        Sort dbSort = SortSupport.buildSort(sort, ALLOWED_SORT_FIELDS);
        List<TransactionRecord> records = dbSort == null
                ? txRepository.findAll(spec)
                : txRepository.findAll(spec, dbSort);
        List<TransactionResponse> transactions = records.stream().map(this::toResponse).collect(Collectors.toList());

        StringBuilder csv = new StringBuilder();
        csv.append("reference,type,amount,fromAccount,toAccount,occurredAt,note\n");
        for (TransactionResponse tx : transactions) {
            csv.append(csvCell(tx.getReference())).append(',')
                    .append(csvCell(tx.getType().name())).append(',')
                    .append(csvCell(tx.getAmount().toPlainString())).append(',')
                    .append(csvCell(tx.getFromAccount())).append(',')
                    .append(csvCell(tx.getToAccount())).append(',')
                    .append(csvCell(tx.getOccurredAt().toString())).append(',')
                    .append(csvCell(tx.getNote()))
                    .append('\n');
        }
        return csv.toString();
    }

    /**
     * Builds the composite filter Specification (user ownership + optional date/amount range)
     * shared by every transaction read path (listing, filtering, summary, CSV export), so date
     * parsing/validation and the ownership predicate are defined exactly once.
     */
    private Specification<TransactionRecord> buildFilterSpecification(
            User user,
            String startDate,
            String endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw new IllegalArgumentException("minAmount must be less than or equal to maxAmount");
        }

        Specification<TransactionRecord> spec = TransactionSpecifications.belongsToUser(user);
        try {
            if (startDate != null && !startDate.isBlank()) {
                Instant start = LocalDate.parse(startDate).atStartOfDay().toInstant(ZoneOffset.UTC);
                spec = spec.and(TransactionSpecifications.occurredOnOrAfter(start));
            }
            if (endDate != null && !endDate.isBlank()) {
                // inclusive end-of-day
                Instant endExclusive = LocalDate.parse(endDate).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                spec = spec.and(TransactionSpecifications.occurredBefore(endExclusive));
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format, expected YYYY-MM-DD");
        }
        if (minAmount != null) {
            spec = spec.and(TransactionSpecifications.amountAtLeast(minAmount));
        }
        if (maxAmount != null) {
            spec = spec.and(TransactionSpecifications.amountAtMost(maxAmount));
        }
        return spec;
    }

    private TransactionResponse toResponse(TransactionRecord tx) {
        return new TransactionResponse(
                tx.getReference(),
                tx.getType(),
                tx.getAmount(),
                tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null,
                tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                tx.getOccurredAt(),
                tx.getNote()
        );
    }

    private Set<String> resolveScopeAccounts(User user, String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return accountRepository.findByUser(user).stream()
                    .map(Account::getAccountNumber)
                    .collect(Collectors.toSet());
        }
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (!account.getUser().getId().equals(user.getId())) {
            throw new SecurityException("Forbidden: not your account");
        }
        Set<String> scoped = new HashSet<>();
        scoped.add(accountNumber);
        return scoped;
    }

    private void accumulateMonthly(Map<String, BigDecimal> monthlyNet, Instant occurredAt, BigDecimal delta) {
        String month = YearMonth.from(occurredAt.atZone(ZoneOffset.UTC)).toString();
        monthlyNet.merge(month, delta, BigDecimal::add);
    }

    private String csvCell(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
