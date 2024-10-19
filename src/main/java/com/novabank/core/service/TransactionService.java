package com.novabank.core.service;

import com.novabank.core.dto.transaction.TransactionResponse;
import com.novabank.core.dto.transaction.TransactionSummaryResponse;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.Account;
import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.TransferIdempotencyRecord;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransferIdempotencyRecordRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository txRepository;
    private final TransferIdempotencyRecordRepository transferIdempotencyRecordRepository;
    private final AuditService auditService;
    private final FraudService fraudService;

    @Transactional
    public String transfer(User user, TransferRequest request) {
        return transfer(user, request, null);
    }

    @Transactional
    public String transfer(User user, TransferRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return performTransfer(user, request);
        }
        String normalizedKey = idempotencyKey.trim();
        if (normalizedKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 100 characters");
        }

        String requestHash = hashTransferRequest(request);
        var existing = transferIdempotencyRecordRepository
                .findByActorUsernameAndIdempotencyKey(user.getUsername(), normalizedKey);
        if (existing.isPresent()) {
            validateIdempotentPayload(existing.get(), requestHash);
            return existing.get().getTransferReference();
        }

        String reference = performTransfer(user, request);
        TransferIdempotencyRecord record = new TransferIdempotencyRecord();
        record.setActorUsername(user.getUsername());
        record.setIdempotencyKey(normalizedKey);
        record.setRequestHash(requestHash);
        record.setTransferReference(reference);
        try {
            transferIdempotencyRecordRepository.save(record);
        } catch (DataIntegrityViolationException ex) {
            // If another request won the race for the same key, return that reference.
            TransferIdempotencyRecord raceWinner = transferIdempotencyRecordRepository
                    .findByActorUsernameAndIdempotencyKey(user.getUsername(), normalizedKey)
                    .orElseThrow(() -> ex);
            validateIdempotentPayload(raceWinner, requestHash);
            return raceWinner.getTransferReference();
        }
        return reference;
    }

    private String performTransfer(User user, TransferRequest request) {
        if (request.getFromAccount().equals(request.getToAccount())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        Account from = accountRepository.findByAccountNumber(request.getFromAccount())
                .orElseThrow(() -> new IllegalArgumentException("From account not found"));
        Account to = accountRepository.findByAccountNumber(request.getToAccount())
                .orElseThrow(() -> new IllegalArgumentException("To account not found"));
        // authorization: user must own the from account
        if (!from.getUser().getId().equals(user.getId())) {
            throw new SecurityException("Forbidden: not your source account");
        }
        if (!from.isActive()) {
            throw new IllegalArgumentException("Source account is inactive");
        }
        if (!to.isActive()) {
            throw new IllegalArgumentException("Destination account is inactive");
        }
        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }
        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));
        accountRepository.save(from);
        accountRepository.save(to);

        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.TRANSFER);
        tx.setFromAccount(from);
        tx.setToAccount(to);
        tx.setAmount(amount);
        tx.setNote(request.getNote());
        txRepository.save(tx);

        auditService.log(user.getUsername(), "TRANSFER", from.getAccountNumber(), tx.getReference(),
                "Transfer to " + to.getAccountNumber() + " amount " + amount);
        fraudService.checkAndLogLargeTransaction(user.getUsername(), from.getAccountNumber(), amount, "LARGE_TRANSFER");
        return tx.getReference();
    }

    @Transactional
    public List<TransactionResponse> listUserTransactions(User user) {
        return txRepository.findByFromAccount_UserOrToAccount_User(user, user)
                .stream()
                .map(tx -> new TransactionResponse(
                        tx.getReference(),
                        tx.getType(),
                        tx.getAmount(),
                        tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null,
                        tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                        tx.getOccurredAt(),
                        tx.getNote()
                ))
                .collect(Collectors.toList());
    }

    @Transactional
    public List<TransactionResponse> listUserTransactionsFiltered(
            User user,
            String startDate,
            String endDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw new IllegalArgumentException("minAmount must be less than or equal to maxAmount");
        }

        Instant start = null;
        Instant end = null;
        try {
            if (startDate != null && !startDate.isBlank()) {
                start = LocalDate.parse(startDate).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (endDate != null && !endDate.isBlank()) {
                // inclusive end-of-day
                end = LocalDate.parse(endDate).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format, expected YYYY-MM-DD");
        }
        final Instant startFinal = start;
        final Instant endFinal = end;

        return txRepository.findByFromAccount_UserOrToAccount_User(user, user)
                .stream()
                .filter(tx -> startFinal == null || !tx.getOccurredAt().isBefore(startFinal))
                .filter(tx -> endFinal == null || tx.getOccurredAt().isBefore(endFinal))
                .filter(tx -> minAmount == null || tx.getAmount().compareTo(minAmount) >= 0)
                .filter(tx -> maxAmount == null || tx.getAmount().compareTo(maxAmount) <= 0)
                .map(tx -> new TransactionResponse(
                        tx.getReference(),
                        tx.getType(),
                        tx.getAmount(),
                        tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null,
                        tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                        tx.getOccurredAt(),
                        tx.getNote()
                ))
                .collect(Collectors.toList());
    }

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
        List<TransactionResponse> base = (startDate == null && endDate == null && minAmount == null && maxAmount == null)
                ? listUserTransactions(user)
                : listUserTransactionsFiltered(user, startDate, endDate, minAmount, maxAmount);

        // sorting
        Comparator<TransactionResponse> comparator = buildComparator(sort);
        if (comparator != null) {
            base = base.stream().sorted(comparator).collect(Collectors.toList());
        }

        // pagination (in-memory to preserve backward compatibility without DB changes)
        if (page != null || size != null) {
            int p = page == null ? 0 : Math.max(0, page);
            int s = size == null ? 20 : Math.max(1, size);
            int fromIdx = Math.min(p * s, base.size());
            int toIdx = Math.min(fromIdx + s, base.size());
            return base.subList(fromIdx, toIdx);
        }
        return base;
    }

    @Transactional
    public TransactionSummaryResponse summarizeUserTransactions(User user, String startDate, String endDate, String accountNumber) {
        List<TransactionResponse> transactions = listUserTransactionsFiltered(user, startDate, endDate, null, null);
        Set<String> scopeAccounts = resolveScopeAccounts(user, accountNumber);
        String scopedAccount = (accountNumber == null || accountNumber.isBlank()) ? null : accountNumber;

        if (scopedAccount != null) {
            transactions = transactions.stream()
                    .filter(tx -> scopedAccount.equals(tx.getFromAccount()) || scopedAccount.equals(tx.getToAccount()))
                    .collect(Collectors.toList());
        }

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
        List<TransactionResponse> transactions = listUserTransactionsFiltered(user, startDate, endDate, minAmount, maxAmount);
        Comparator<TransactionResponse> comparator = buildComparator(sort);
        if (comparator != null) {
            transactions = transactions.stream().sorted(comparator).collect(Collectors.toList());
        }

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

    private Comparator<TransactionResponse> buildComparator(String sort) {
        if (sort == null || sort.isBlank()) return null;
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        String dir = parts.length > 1 ? parts[1].trim().toLowerCase() : "asc";
        boolean desc = dir.equals("desc");

        Comparator<TransactionResponse> comp;
        switch (field) {
            case "occurredAt":
                comp = Comparator.comparing(TransactionResponse::getOccurredAt);
                break;
            case "amount":
                comp = Comparator.comparing(TransactionResponse::getAmount);
                break;
            case "type":
                comp = Comparator.comparing(tr -> tr.getType().name());
                break;
            default:
                throw new IllegalArgumentException("Invalid sort field. Allowed: occurredAt, amount, type");
        }
        return desc ? comp.reversed() : comp;
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

    private String hashTransferRequest(TransferRequest request) {
        String note = request.getNote() == null ? "" : request.getNote().trim();
        String payload = String.join("|",
                request.getFromAccount(),
                request.getToAccount(),
                request.getAmount().stripTrailingZeros().toPlainString(),
                note
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void validateIdempotentPayload(TransferIdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IllegalArgumentException("Idempotency-Key already used with different transfer payload");
        }
    }

    private String csvCell(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
