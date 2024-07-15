package com.novabank.core.dto.account;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class AccountResponse {
    private String accountNumber;
    private BigDecimal balance;
    private boolean active;
}
