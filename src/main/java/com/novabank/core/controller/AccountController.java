package com.novabank.core.controller;

import com.novabank.core.dto.account.AccountResponse;
import com.novabank.core.dto.transaction.DepositWithdrawRequest;
import com.novabank.core.model.User;
import com.novabank.core.service.AccountService;
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

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Manage bank accounts for the current user")
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "List current user accounts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accounts listed",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.account.AccountResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<List<AccountResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(accountService.listAccounts(user));
    }

    @Operation(summary = "Create a new bank account for the current user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account created",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.account.AccountResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<AccountResponse> create(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(accountService.createAccount(user));
    }

    @Operation(summary = "Deposit funds into an account owned by the current user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deposit successful",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.account.AccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden: not your account",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PostMapping("/deposit")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<AccountResponse> deposit(@AuthenticationPrincipal User user,
                                                   @Valid @RequestBody DepositWithdrawRequest request) {
        return ResponseEntity.ok(accountService.deposit(user, request.getAccountNumber(), request.getAmount(), request.getNote()));
    }

    @Operation(summary = "Withdraw funds from an account owned by the current user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdraw successful",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.account.AccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error or insufficient funds",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden: not your account",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PostMapping("/withdraw")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<AccountResponse> withdraw(@AuthenticationPrincipal User user,
                                                    @Valid @RequestBody DepositWithdrawRequest request) {
        return ResponseEntity.ok(accountService.withdraw(user, request.getAccountNumber(), request.getAmount(), request.getNote()));
    }
}
