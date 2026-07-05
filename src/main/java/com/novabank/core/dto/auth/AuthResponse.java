package com.novabank.core.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * {@code token} is the short-lived (15 min default) JWT access token, kept under its original
 * field name for backward compatibility with existing clients/tests. {@code refreshToken} is the
 * new, longer-lived (7 day default), server-revocable opaque token — see
 * {@code RefreshTokenService} — used against {@code POST /api/v1/auth/refresh} to obtain a new
 * access token without re-authenticating, and against {@code POST /api/v1/auth/logout} to
 * revoke the session.
 */
@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String refreshToken;
}
