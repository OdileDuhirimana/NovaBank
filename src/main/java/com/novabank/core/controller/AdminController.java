package com.novabank.core.controller;

import com.novabank.core.model.AuditLog;
import com.novabank.core.model.FraudLog;
import com.novabank.core.repository.AuditLogRepository;
import com.novabank.core.repository.FraudLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Administration", description = "Audit and fraud log access for ADMIN/AUDITOR roles")
public class AdminController {

    private final AuditLogRepository auditLogRepository;
    private final FraudLogRepository fraudLogRepository;

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
    public ResponseEntity<Page<AuditLog>> auditLogs(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
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
    public ResponseEntity<Page<FraudLog>> fraudLogs(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(fraudLogRepository.findAll(PageRequest.of(page, size)));
    }
}
