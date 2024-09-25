package com.novabank.core.dto.transaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
public class TransactionSummaryResponse {
    private String scopeAccountNumber;
    private String startDate;
    private String endDate;
    private long transactionCount;
    private long internalTransferCount;
    private BigDecimal totalCredits;
    private BigDecimal totalDebits;
    private BigDecimal netCashflow;
    private BigDecimal largestCredit;
    private BigDecimal largestDebit;
    private Map<String, BigDecimal> monthlyNetCashflow;
}
