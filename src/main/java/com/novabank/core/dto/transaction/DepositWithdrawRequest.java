package com.novabank.core.dto.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositWithdrawRequest {
    @NotBlank
    private String accountNumber;

    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    @Size(max = 200, message = "Note must be at most 200 characters")
    private String note;
}
