package com.novabank.core.service;

import com.novabank.core.dto.account.AccountResponse;
import com.novabank.core.model.Account;
import com.novabank.core.model.TransactionRecord;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.TransactionRecordRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private final TransactionRecordRepository txRepository;
    private final AuditService auditService;
    private final FraudService fraudService;

    private final Random random = new SecureRandom();

    public List<AccountResponse> listAccounts(User user) {
        return accountRepository.findByUser(user).stream()
                .map(a -> new AccountResponse(a.getAccountNumber(), a.getBalance(), a.isActive()))
                .collect(Collectors.toList());
    }

    @Transactional
    public AccountResponse createAccount(User user) {
        Account account = new Account();
        account.setUser(user);
        account.setAccountNumber(generateUniqueAccountNumber());
        account.setBalance(BigDecimal.ZERO);
        account.setActive(true);
        accountRepository.save(account);
        auditService.log(user.getUsername(), "ACCOUNT_CREATE", account.getAccountNumber(), null, "Account created");
        return new AccountResponse(account.getAccountNumber(), account.getBalance(), account.isActive());
    }

    @Transactional
    public AccountResponse deposit(User user, String accountNumber, BigDecimal amount, String note) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        requireOwner(user, account);
        requireActive(account);
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.DEPOSIT);
        tx.setToAccount(account);
        tx.setAmount(amount);
        tx.setNote(note);
        txRepository.save(tx);

        auditService.log(user.getUsername(), "DEPOSIT", accountNumber, tx.getReference(), "Deposit " + amount);
        fraudService.checkAndLogLargeTransaction(user.getUsername(), accountNumber, amount, "LARGE_DEPOSIT");
        return new AccountResponse(account.getAccountNumber(), account.getBalance(), account.isActive());
    }

    @Transactional
    public AccountResponse withdraw(User user, String accountNumber, BigDecimal amount, String note) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        requireOwner(user, account);
        requireActive(account);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.WITHDRAWAL);
        tx.setFromAccount(account);
        tx.setAmount(amount);
        tx.setNote(note);
        txRepository.save(tx);

        auditService.log(user.getUsername(), "WITHDRAW", accountNumber, tx.getReference(), "Withdraw " + amount);
        fraudService.checkAndLogLargeTransaction(user.getUsername(), accountNumber, amount, "LARGE_WITHDRAWAL");
        return new AccountResponse(account.getAccountNumber(), account.getBalance(), account.isActive());
    }

    @Transactional
    public AccountResponse updateAccountStatus(User actor, String accountNumber, boolean active, String reason) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        account.setActive(active);
        accountRepository.save(account);

        String action = active ? "ACCOUNT_ACTIVATE" : "ACCOUNT_FREEZE";
        String details = (reason == null || reason.isBlank())
                ? "Account status updated to " + (active ? "active" : "inactive")
                : "Account status updated to " + (active ? "active" : "inactive") + ": " + reason;
        auditService.log(actor.getUsername(), action, accountNumber, null, details);

        return new AccountResponse(account.getAccountNumber(), account.getBalance(), account.isActive());
    }

    private void requireOwner(User user, Account account) {
        if (!account.getUser().getId().equals(user.getId())) {
            throw new SecurityException("Forbidden: not your account");
        }
    }

    private void requireActive(Account account) {
        if (!account.isActive()) {
            throw new IllegalArgumentException("Account is inactive");
        }
    }

    private String generateUniqueAccountNumber() {
        String acc;
        do {
            acc = String.format("%04d-%04d-%04d", random.nextInt(10000), random.nextInt(10000), random.nextInt(10000));
        } while (accountRepository.existsByAccountNumber(acc));
        return acc;
    }
}
