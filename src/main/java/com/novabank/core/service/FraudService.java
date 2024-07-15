package com.novabank.core.service;

import com.novabank.core.model.FraudLog;
import com.novabank.core.repository.FraudLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class FraudService {
    private final FraudLogRepository fraudLogRepository;

    private static final BigDecimal LARGE_TX_THRESHOLD = new BigDecimal("10000.00");

    public boolean checkAndLogLargeTransaction(String username, String accountNumber, BigDecimal amount, String eventType) {
        if (amount.compareTo(LARGE_TX_THRESHOLD) >= 0) {
            FraudLog log = new FraudLog();
            log.setEventType(eventType);
            log.setUsername(username);
            log.setAccountNumber(accountNumber);
            log.setDetails("Large amount detected: " + amount);
            log.setFlagged(true);
            fraudLogRepository.save(log);
            return true;
        }
        return false;
    }

    public void logFailedLogin(String username) {
        FraudLog log = new FraudLog();
        log.setEventType("FAILED_LOGIN");
        log.setUsername(username);
        log.setDetails("Failed login attempt");
        log.setFlagged(true);
        fraudLogRepository.save(log);
    }
}
