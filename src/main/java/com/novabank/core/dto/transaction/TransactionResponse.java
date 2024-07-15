package com.novabank.core.dto.transaction;

import com.novabank.core.model.TransactionRecord;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@AllArgsConstructor
public class TransactionResponse {
    private String reference;
    private TransactionRecord.Type type;
    private BigDecimal amount;
    private String fromAccount;
    private String toAccount;
    private Instant occurredAt;
    private String note;
}
