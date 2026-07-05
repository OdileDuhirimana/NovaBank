package com.novabank.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A server-revocable refresh token, persisted only as a SHA-256 hash (never the raw value — see
 * {@code RefreshTokenService}) so a database read/backup leak alone cannot be used to
 * impersonate a user.
 *
 * WHY THIS EXISTS: the security audit's Critical Issue #5 was "no refresh-token revocation — a
 * leaked JWT remains valid for its full default lifetime with no server-side kill switch."
 * Access tokens ({@code security.jwt.secret}-signed JWTs) are stateless by design and cannot be
 * revoked without a blacklist; instead of adding one, this project shortens the access token
 * lifetime to 15 minutes (see {@code application.yml}) and introduces this longer-lived (7 day
 * default),
 * database-backed, individually-revocable refresh token. Logging out, or detecting reuse of an
 * already-rotated token (a strong signal of theft — see {@code RefreshTokenService#rotate}),
 * revokes it (or the user's entire token family) immediately, closing the "no kill switch" gap
 * without the operational cost of a full access-token blacklist.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column
    private Instant revokedAt;
}
