package com.novabank.core.service;

import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.Account;
import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.TransferIdempotencyRecord;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import com.novabank.core.repository.TransferIdempotencyRecordRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the single write path for money movement: transfer orchestration, ownership/balance
 * invariant checks, and idempotency-key handling.
 *
 * Split out of the former monolithic {@code TransactionService} (which also handled listing,
 * filtering, summarization, and CSV export — seven injected collaborators worth of unrelated
 * responsibilities) to restore single-responsibility at the class level. This is a command
 * (write) service; {@link TransactionQueryService} is its read-side counterpart. Neither depends
 * on the other, and callers (currently only {@code TransactionController}) depend on whichever
 * one matches the operation they need — a lightweight CQRS-style separation appropriate at this
 * project's scale (a single shared persistence store, not physically separate read/write models).
 */
@Service
@RequiredArgsConstructor
public class TransactionCommandService {

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository txRepository;
    private final TransferIdempotencyRecordRepository transferIdempotencyRecordRepository;
    private final AuditService auditService;
    private final FraudService fraudService;
    private final WebhookOutboxService webhookOutboxService;

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
        boolean flagged = fraudService.checkAndLogLargeTransaction(user.getUsername(), from.getAccountNumber(), amount, "LARGE_TRANSFER");
        if (flagged) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actor", user.getUsername());
            payload.put("fromAccount", from.getAccountNumber());
            payload.put("toAccount", to.getAccountNumber());
            payload.put("amount", amount);
            payload.put("reference", tx.getReference());
            payload.put("note", request.getNote() == null ? "" : request.getNote());
            // Enqueued to the outbox (a fast local DB insert, part of this same transaction)
            // rather than dispatched synchronously here — see WebhookOutboxEvent Javadoc. This
            // is the fix for the code review's Critical Issue #1: no external HTTP call is ever
            // made while this method holds its transaction open.
            webhookOutboxService.enqueue("LARGE_TRANSFER", payload);
        }
        return tx.getReference();
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
}
