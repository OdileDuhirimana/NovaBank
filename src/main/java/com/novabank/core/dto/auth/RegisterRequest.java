package com.novabank.core.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Public self-registration request.
 *
 * Security note: this DTO intentionally has no {@code role} field. Every self-registered
 * account is always created as {@code CUSTOMER} (enforced server-side in
 * {@code UserService.register()}). Elevated roles (ADMIN, AUDITOR) must be granted through a
 * separate authenticated ADMIN-only action, never through public registration input — a prior
 * version of this class exposed a client-settable {@code role} field that allowed any
 * unauthenticated caller to self-provision an ADMIN account.
 */
@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    @Email
    @NotBlank
    private String email;

    @NotBlank
    @Size(min = 8, max = 100)
    private String password;
}
