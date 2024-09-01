package com.novabank.core.service;

import com.novabank.core.dto.transaction.TransactionResponse;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.Account;
import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository txRepository;
    private final AuditService auditService;
    private final FraudService fraudService;

    @Transactional
    public String transfer(User user, TransferRequest request) {
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
}
