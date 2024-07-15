package com.novabank.core.dto.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferRequest {
    @NotBlank
    private String fromAccount;
    @NotBlank
    private String toAccount;

    @DecimalMin("0.01")
    private BigDecimal amount;

    @Size(max = 200, message = "Note must be at most 200 characters")
    private String note;
}
