package com.novabank.core.controller;

import com.novabank.core.dto.transaction.TransactionResponse;
import com.novabank.core.dto.transaction.TransactionSummaryResponse;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.User;
import com.novabank.core.service.TransactionService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "View transaction history and transfer funds")
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(summary = "List transaction history for the current user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "History returned",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.transaction.TransactionResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/my")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<List<TransactionResponse>> my(
            @AuthenticationPrincipal User user,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "minAmount", required = false) BigDecimal minAmount,
            @RequestParam(name = "maxAmount", required = false) BigDecimal maxAmount,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return ResponseEntity.ok(
                transactionService.listUserTransactionsWithOptions(
                        user, startDate, endDate, minAmount, maxAmount, page, size, sort
                )
        );
    }

    @Operation(summary = "Get transaction cashflow summary for the current user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary returned",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.transaction.TransactionSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/summary")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<TransactionSummaryResponse> summary(
            @AuthenticationPrincipal User user,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "accountNumber", required = false) String accountNumber
    ) {
        return ResponseEntity.ok(transactionService.summarizeUserTransactions(user, startDate, endDate, accountNumber));
    }

    @Operation(summary = "Export transaction statement as CSV")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement returned as CSV"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping(value = "/statement", produces = "text/csv")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<String> statement(
            @AuthenticationPrincipal User user,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "minAmount", required = false) BigDecimal minAmount,
            @RequestParam(name = "maxAmount", required = false) BigDecimal maxAmount,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        String csv = transactionService.buildStatementCsv(user, startDate, endDate, minAmount, maxAmount, sort);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transaction-statement.csv\"")
                .contentType(MediaType.valueOf("text/csv"))
                .body(csv);
    }

    @Operation(summary = "Transfer funds between accounts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer queued",
                    content = @Content(schema = @Schema(implementation = java.util.Map.class))),
            @ApiResponse(responseCode = "400", description = "Validation error or insufficient funds",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden: not your source account",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PostMapping("/transfer")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Map<String, String>> transfer(@AuthenticationPrincipal User user,
                                                        @Parameter(description = "Optional idempotency key to safely retry transfer requests")
                                                        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                        @Valid @RequestBody TransferRequest request) {
        String ref = transactionService.transfer(user, request, idempotencyKey);
        return ResponseEntity.ok(Map.of("reference", ref));
    }
}
