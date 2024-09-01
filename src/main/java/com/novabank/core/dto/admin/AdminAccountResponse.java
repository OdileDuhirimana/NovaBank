package com.novabank.core.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@AllArgsConstructor
public class AdminAccountResponse {
    private String accountNumber;
    private BigDecimal balance;
    private boolean active;
    private String ownerUsername;
    private Instant createdAt;
}
