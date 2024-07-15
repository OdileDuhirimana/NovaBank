package com.novabank.core.config;

import com.novabank.core.model.*;
import com.novabank.core.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class HistoricalDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataSeeder.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRecordRepository txRepository;
    private final AuditLogRepository auditLogRepository;
    private final FraudLogRepository fraudLogRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.historical.enabled:false}")
    private boolean enabled;
    @Value("${app.bootstrap.historical.months:3}")
    private int monthsToSeed;
    @Value("${app.bootstrap.historical.users:3}")
    private int usersToSeed;
    @Value("${app.bootstrap.historical.tx-per-week:6}")
    private int txPerWeek;

    private final Random random = new SecureRandom();

    @Bean
    CommandLineRunner seedHistoricalData() {
        return args -> {
            if (!enabled) {
                log.info("Historical data seeding disabled. Skipping.");
                return;
            }

            // Idempotency: if a SEED_HISTORICAL marker exists in AuditLog for current monthsToSeed, skip.
            boolean alreadySeeded = auditLogRepository.findAll().stream()
                    .anyMatch(a -> "system".equals(a.getActor()) && "SEED_HISTORICAL".equals(a.getAction())
                            && a.getDetails() != null && a.getDetails().contains("months=" + monthsToSeed));
            if (alreadySeeded) {
                log.info("Historical data already seeded for months={} — skipping.", monthsToSeed);
                return;
            }

            log.info("Seeding historical data: months={}, users={}, tx/week={}", monthsToSeed, usersToSeed, txPerWeek);

            // Determine time window
            Instant now = Instant.now();
            ZonedDateTime zNow = now.atZone(ZoneOffset.UTC);
            ZonedDateTime zStart = zNow.minusMonths(Math.max(1, monthsToSeed)).withHour(9).withMinute(0).withSecond(0).withNano(0);

            // 1) Ensure users exist (create if missing)
            List<User> users = ensureUsers(usersToSeed, zStart.toInstant());

            // 2) Ensure accounts (2 per user)
            Map<User, List<Account>> userAccounts = ensureAccounts(users, zStart.toInstant());

            // 3) Generate weekly transactions across the period
            generateTransactionsAndLogs(zStart, zNow, userAccounts);

            // 4) Add seeding marker
            AuditLog marker = new AuditLog();
            marker.setActor("system");
            marker.setAction("SEED_HISTORICAL");
            marker.setDetails("Seeded historical data months=" + monthsToSeed + ", users=" + usersToSeed + ", txPerWeek=" + txPerWeek);
            Instant markerTime = zNow.minusSeconds(5).toInstant();
            marker.setCreatedAt(markerTime);
            marker.setUpdatedAt(markerTime);
            auditLogRepository.save(marker);

            log.info("Historical data seeding completed.");
        };
    }

    private List<User> ensureUsers(int count, Instant baseCreatedAt) {
        List<String> defaultNames = List.of("alice", "bob", "charlie", "dave", "eve", "frank", "grace", "heidi");
        List<User> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String username = (i < defaultNames.size()) ? defaultNames.get(i) : ("seeduser" + (i + 1));
            Optional<User> existing = userRepository.findByUsername(username);
            if (existing.isPresent()) {
                users.add(existing.get());
                continue;
            }
            User u = new User();
            u.setUsername(username);
            u.setEmail(username + "@nova.local");
            u.setPasswordHash(passwordEncoder.encode("password"));
            u.setRole(Role.CUSTOMER);
            // Backdate user creation slightly after base
            Instant created = baseCreatedAt.plusSeconds(randomBetween(0, 7 * 24 * 3600));
            u.setCreatedAt(created);
            u.setUpdatedAt(created);
            users.add(userRepository.save(u));
            log.info("Seeded user '{}'.", username);
        }
        return users;
    }

    private Map<User, List<Account>> ensureAccounts(List<User> users, Instant baseCreatedAt) {
        Map<User, List<Account>> result = new HashMap<>();
        for (User u : users) {
            List<Account> accounts = accountRepository.findByUser(u);
            if (accounts.size() < 2) {
                int needed = 2 - accounts.size();
                for (int i = 0; i < needed; i++) {
                    Account a = new Account();
                    a.setUser(u);
                    a.setAccountNumber(generateUniqueAccountNumber());
                    a.setBalance(BigDecimal.ZERO);
                    a.setActive(true);
                    Instant created = baseCreatedAt.plusSeconds(randomBetween(7 * 24 * 3600, 21 * 24 * 3600));
                    a.setCreatedAt(created);
                    a.setUpdatedAt(created);
                    accountRepository.save(a);
                    accounts.add(a);
                    log.info("Created account {} for {}", a.getAccountNumber(), u.getUsername());
                }
            }
            result.put(u, accounts);
        }
        return result;
    }

    private void generateTransactionsAndLogs(ZonedDateTime zStart, ZonedDateTime zEnd, Map<User, List<Account>> userAccounts) {
        // Prepare a flat list of all accounts for cross-user transfers
        List<Account> allAccounts = userAccounts.values().stream().flatMap(List::stream).collect(Collectors.toList());

        ZonedDateTime cursor = zStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        while (!cursor.isAfter(zEnd)) {
            ZonedDateTime weekStart = cursor;
            ZonedDateTime weekEnd = cursor.plusDays(6).withHour(18);
            for (Map.Entry<User, List<Account>> entry : userAccounts.entrySet()) {
                User user = entry.getKey();
                List<Account> accounts = entry.getValue();
                if (accounts.isEmpty()) continue;

                int ops = Math.max(1, txPerWeek + random.nextInt(3) - 1); // small variance
                for (int i = 0; i < ops; i++) {
                    int action = random.nextInt(100);
                    if (action < 45) {
                        // Deposit
                        Account to = accounts.get(random.nextInt(accounts.size()));
                        BigDecimal amount = randomMoney(50, 5000);
                        // Occasionally make a large deposit to create FraudLog
                        if (random.nextInt(100) < 5) amount = randomMoney(12000, 25000);
                        Instant when = randomInstant(weekStart, weekEnd);
                        createDeposit(user, to, amount, when, "Weekly deposit");
                    } else if (action < 75) {
                        // Withdraw (if funds available)
                        Account from = accounts.get(random.nextInt(accounts.size()));
                        if (from.getBalance().compareTo(new BigDecimal("20")) > 0) {
                            BigDecimal max = from.getBalance().min(new BigDecimal("4000"));
                            if (max.compareTo(new BigDecimal("20")) > 0) {
                                BigDecimal amount = randomMoney(20, max.intValue());
                                Instant when = randomInstant(weekStart, weekEnd);
                                createWithdrawal(user, from, amount, when, "ATM withdrawal");
                            }
                        }
                    } else {
                        // Transfer (intra- or inter-user)
                        Account from = accounts.get(random.nextInt(accounts.size()));
                        Account to = allAccounts.get(random.nextInt(allAccounts.size()));
                        if (to.getId().equals(from.getId())) continue;
                        if (from.getBalance().compareTo(new BigDecimal("30")) > 0) {
                            BigDecimal max = from.getBalance().min(new BigDecimal("6000"));
                            if (max.compareTo(new BigDecimal("30")) > 0) {
                                BigDecimal amount = randomMoney(30, max.intValue());
                                Instant when = randomInstant(weekStart, weekEnd);
                                createTransfer(user, from, to, amount, when, "Transfer");
                            }
                        }
                    }
                }
            }
            cursor = cursor.plusWeeks(1);
        }
    }

    private void createDeposit(User user, Account to, BigDecimal amount, Instant when, String note) {
        to.setBalance(to.getBalance().add(amount));
        accountRepository.save(to);

        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.DEPOSIT);
        tx.setToAccount(to);
        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setCreatedAt(when);
        tx.setUpdatedAt(when);
        txRepository.save(tx);

        AuditLog al = new AuditLog();
        al.setActor(user.getUsername());
        al.setAction("DEPOSIT");
        al.setAccountNumber(to.getAccountNumber());
        al.setReference(tx.getReference());
        al.setDetails("Deposit " + amount);
        al.setCreatedAt(when);
        al.setUpdatedAt(when);
        auditLogRepository.save(al);

        // Fraud log if large
        if (amount.compareTo(new BigDecimal("10000.00")) >= 0) {
            FraudLog fl = new FraudLog();
            fl.setEventType("LARGE_DEPOSIT");
            fl.setUsername(user.getUsername());
            fl.setAccountNumber(to.getAccountNumber());
            fl.setDetails("Large amount detected: " + amount);
            fl.setFlagged(true);
            fl.setCreatedAt(when);
            fl.setUpdatedAt(when);
            fraudLogRepository.save(fl);
        }
    }

    private void createWithdrawal(User user, Account from, BigDecimal amount, Instant when, String note) {
        if (from.getBalance().compareTo(amount) < 0) return;
        from.setBalance(from.getBalance().subtract(amount));
        accountRepository.save(from);

        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.WITHDRAWAL);
        tx.setFromAccount(from);
        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setCreatedAt(when);
        tx.setUpdatedAt(when);
        txRepository.save(tx);

        AuditLog al = new AuditLog();
        al.setActor(user.getUsername());
        al.setAction("WITHDRAW");
        al.setAccountNumber(from.getAccountNumber());
        al.setReference(tx.getReference());
        al.setDetails("Withdraw " + amount);
        al.setCreatedAt(when);
        al.setUpdatedAt(when);
        auditLogRepository.save(al);

        if (amount.compareTo(new BigDecimal("10000.00")) >= 0) {
            FraudLog fl = new FraudLog();
            fl.setEventType("LARGE_WITHDRAWAL");
            fl.setUsername(user.getUsername());
            fl.setAccountNumber(from.getAccountNumber());
            fl.setDetails("Large amount detected: " + amount);
            fl.setFlagged(true);
            fl.setCreatedAt(when);
            fl.setUpdatedAt(when);
            fraudLogRepository.save(fl);
        }
    }

    private void createTransfer(User actor, Account from, Account to, BigDecimal amount, Instant when, String note) {
        if (from.getBalance().compareTo(amount) < 0) return;
        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));
        accountRepository.save(from);
        accountRepository.save(to);

        TransactionRecord tx = new TransactionRecord();
        tx.setType(TransactionRecord.Type.TRANSFER);
        tx.setFromAccount(from);
        tx.setToAccount(to);
        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setCreatedAt(when);
        tx.setUpdatedAt(when);
        txRepository.save(tx);

        AuditLog al = new AuditLog();
        al.setActor(actor.getUsername());
        al.setAction("TRANSFER");
        al.setAccountNumber(from.getAccountNumber());
        al.setReference(tx.getReference());
        al.setDetails("Transfer to " + to.getAccountNumber() + " amount " + amount);
        al.setCreatedAt(when);
        al.setUpdatedAt(when);
        auditLogRepository.save(al);

        if (amount.compareTo(new BigDecimal("10000.00")) >= 0) {
            FraudLog fl = new FraudLog();
            fl.setEventType("LARGE_TRANSFER");
            fl.setUsername(actor.getUsername());
            fl.setAccountNumber(from.getAccountNumber());
            fl.setDetails("Large amount detected: " + amount);
            fl.setFlagged(true);
            fl.setCreatedAt(when);
            fl.setUpdatedAt(when);
            fraudLogRepository.save(fl);
        }
    }

    private String generateUniqueAccountNumber() {
        String acc;
        do {
            acc = String.format("%04d-%04d-%04d", random.nextInt(10000), random.nextInt(10000), random.nextInt(10000));
        } while (accountRepository.existsByAccountNumber(acc));
        return acc;
    }

    private long randomBetween(long minInclusive, long maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        long range = maxInclusive - minInclusive + 1;
        long r = Math.abs(random.nextLong());
        return minInclusive + (r % range);
    }

    private BigDecimal randomMoney(int min, int max) {
        if (max < min) max = min;
        int cents = random.nextInt(100);
        int val = min + random.nextInt(max - min + 1);
        return new BigDecimal(val + "." + String.format("%02d", cents));
    }

    private Instant randomInstant(ZonedDateTime start, ZonedDateTime end) {
        long s = start.toInstant().toEpochMilli();
        long e = end.toInstant().toEpochMilli();
        long t = s + Math.abs(random.nextLong()) % Math.max(1, (e - s));
        return Instant.ofEpochMilli(t);
    }
}
