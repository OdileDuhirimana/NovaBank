package com.novabank.core.controller;

import com.novabank.core.dto.account.AccountResponse;
import com.novabank.core.dto.admin.AccountStatusUpdateRequest;
import com.novabank.core.dto.admin.AdminAccountResponse;
import com.novabank.core.model.Account;
import com.novabank.core.model.AuditLog;
import com.novabank.core.model.FraudLog;
import com.novabank.core.model.User;
import com.novabank.core.repository.AccountRepository;
import com.novabank.core.repository.AuditLogRepository;
import com.novabank.core.repository.FraudLogRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Administration", description = "Audit and fraud log access for ADMIN/AUDITOR roles")
public class AdminController {

    private final AccountRepository accountRepository;
    private final AuditLogRepository auditLogRepository;
    private final FraudLogRepository fraudLogRepository;
    private final AccountService accountService;

    @Operation(summary = "List accounts for administration (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accounts returned",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.admin.AdminAccountResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Page<AdminAccountResponse>> accounts(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "username", required = false) String username
    ) {
        var pageable = PageRequest.of(page, size);
        Page<Account> result;
        boolean hasUsername = username != null && !username.isBlank();

        if (active != null && hasUsername) {
            result = accountRepository.findByActiveAndUser_UsernameContainingIgnoreCase(active, username, pageable);
        } else if (active != null) {
            result = accountRepository.findByActive(active, pageable);
        } else if (hasUsername) {
            result = accountRepository.findByUser_UsernameContainingIgnoreCase(username, pageable);
        } else {
            result = accountRepository.findAll(pageable);
        }

        Page<AdminAccountResponse> body = result.map(a -> new AdminAccountResponse(
                a.getAccountNumber(),
                a.getBalance(),
                a.isActive(),
                a.getUser().getUsername(),
                a.getCreatedAt()
        ));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Freeze or reactivate an account (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account status updated",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.account.AccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation or bad request error",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @PatchMapping("/accounts/{accountNumber}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<AccountResponse> updateAccountStatus(
            @AuthenticationPrincipal User actor,
            @PathVariable String accountNumber,
            @Valid @RequestBody AccountStatusUpdateRequest request
    ) {
        AccountResponse response = accountService.updateAccountStatus(
                actor,
                accountNumber,
                request.getActive(),
                request.getReason()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List audit logs (ADMIN/AUDITOR)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit logs returned",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.model.AuditLog.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Page<AuditLog>> auditLogs(@RequestParam(name = "page", defaultValue = "0") int page,
                                                    @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(auditLogRepository.findAll(PageRequest.of(page, size)));
    }

    @Operation(summary = "List fraud logs (ADMIN/AUDITOR)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fraud logs returned",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.model.FraudLog.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/fraud")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Page<FraudLog>> fraudLogs(@RequestParam(name = "page", defaultValue = "0") int page,
                                                    @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(fraudLogRepository.findAll(PageRequest.of(page, size)));
    }
}
