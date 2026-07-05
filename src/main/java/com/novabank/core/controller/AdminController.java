package com.novabank.core.controller;

import com.novabank.core.common.PaginationDefaults;
import com.novabank.core.dto.account.AccountResponse;
import com.novabank.core.dto.admin.AccountStatusUpdateRequest;
import com.novabank.core.dto.admin.AdminAccountResponse;
import com.novabank.core.dto.admin.AuditLogResponse;
import com.novabank.core.dto.admin.FraudLogResponse;
import com.novabank.core.model.User;
import com.novabank.core.service.AccountService;
import com.novabank.core.service.AdminService;
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

/**
 * Thin REST layer for administration/oversight endpoints. All persistence access and audit-read
 * logging is delegated to {@link AdminService} / {@link AccountService} — this controller has no
 * repository dependencies (see {@code ArchitectureFitnessTests} for the mechanically-enforced
 * rule), matching every other controller in this codebase.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Administration", description = "Audit and fraud log access for ADMIN/AUDITOR roles")
public class AdminController {

    private final AdminService adminService;
    private final AccountService accountService;

    @Operation(summary = "List accounts for administration (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accounts returned",
                    content = @Content(schema = @Schema(implementation = AdminAccountResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Page<AdminAccountResponse>> accounts(
            @AuthenticationPrincipal User actor,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = PaginationDefaults.DEFAULT_PAGE_SIZE_STR) int size,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return ResponseEntity.ok(adminService.listAccounts(actor.getUsername(), active, username, page, size, sort));
    }

    @Operation(summary = "Freeze or reactivate an account (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account status updated",
                    content = @Content(schema = @Schema(implementation = AccountResponse.class))),
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
            @PathVariable("accountNumber") String accountNumber,
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

    @Operation(summary = "List audit logs (ADMIN/AUDITOR)",
            description = "Every call to this endpoint is itself recorded as an ADMIN_AUDIT_LOG_READ audit event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit logs returned",
                    content = @Content(schema = @Schema(implementation = AuditLogResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Page<AuditLogResponse>> auditLogs(
            @AuthenticationPrincipal User actor,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = PaginationDefaults.DEFAULT_PAGE_SIZE_STR) int size,
            @RequestParam(name = "actor", required = false) String actorFilter,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return ResponseEntity.ok(adminService.listAuditLogs(actor.getUsername(), actorFilter, action, page, size, sort));
    }

    @Operation(summary = "List fraud logs (ADMIN/AUDITOR)",
            description = "Every call to this endpoint is itself recorded as an ADMIN_FRAUD_LOG_READ audit event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fraud logs returned",
                    content = @Content(schema = @Schema(implementation = FraudLogResponse.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = com.novabank.core.dto.common.ErrorResponse.class)))
    })
    @GetMapping("/fraud")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Page<FraudLogResponse>> fraudLogs(
            @AuthenticationPrincipal User actor,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = PaginationDefaults.DEFAULT_PAGE_SIZE_STR) int size,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return ResponseEntity.ok(adminService.listFraudLogs(actor.getUsername(), username, eventType, page, size, sort));
    }
}
