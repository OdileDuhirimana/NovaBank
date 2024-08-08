package com.novabank.core.controller;

import com.novabank.core.dto.transaction.TransactionResponse;
import com.novabank.core.dto.transaction.TransferRequest;
import com.novabank.core.model.User;
import com.novabank.core.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
                                                        @Valid @RequestBody TransferRequest request) {
        String ref = transactionService.transfer(user, request);
        return ResponseEntity.ok(Map.of("reference", ref));
    }
}
