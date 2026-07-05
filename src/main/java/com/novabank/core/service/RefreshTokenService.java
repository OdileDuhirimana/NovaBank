package com.novabank.core.service;

import com.novabank.core.common.HashUtil;
import com.novabank.core.model.RefreshToken;
import com.novabank.core.model.User;
import com.novabank.core.repository.RefreshTokenRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Issues, rotates, and revokes refresh tokens — see {@link RefreshToken} Javadoc for the full
 * security rationale (closing Critical Issue #5: no server-side kill switch for a leaked token).
 *
 * Rotation-with-reuse-detection: every successful {@link #rotate(String)} call revokes the
 * presented token and issues a brand new one (the client must always use the newest token).
 * If a caller ever presents a token that has already been revoked, that can only happen if the
 * same refresh token was used twice — the strongest practical signal that it was stolen and used
 * by both the legitimate client and an attacker. Rather than trust either party, this revokes
 * every active refresh token belonging to that user, forcing a fresh login everywhere.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;

    @Value("${security.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    public record IssuedRefreshToken(String rawToken, Instant expiresAt) {
    }

    public record RotationResult(User user, IssuedRefreshToken issuedRefreshToken) {
    }

    @Transactional
    public IssuedRefreshToken issue(User user) {
        String rawToken = generateOpaqueToken();
        Instant expiresAt = Instant.now().plusMillis(refreshExpirationMs);

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(HashUtil.sha256Hex(rawToken));
        entity.setExpiresAt(expiresAt);
        entity.setRevoked(false);
        repository.save(entity);

        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    /**
     * Validates and rotates a refresh token, returning the owning user and a freshly-issued
     * replacement.
     *
     * @throws BadCredentialsException if the token is unknown or expired.
     * @throws SecurityException       if the token has already been used once (reuse detected) —
     *                                  every active refresh token for the user is revoked as a
     *                                  side effect before this is thrown.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken existing = repository.findByTokenHash(HashUtil.sha256Hex(rawToken))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (existing.isRevoked()) {
            log.warn("Refresh token reuse detected for user '{}' — revoking all active refresh tokens.",
                    existing.getUser().getUsername());
            revokeAllActiveTokensFor(existing.getUser());
            throw new SecurityException("Refresh token reuse detected; all sessions have been revoked");
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Refresh token has expired");
        }

        existing.setRevoked(true);
        existing.setRevokedAt(Instant.now());
        repository.save(existing);

        return new RotationResult(existing.getUser(), issue(existing.getUser()));
    }

    /**
     * Revokes a single refresh token (logout). Deliberately silent/idempotent if the token is
     * unknown or already revoked — a logout endpoint must never leak whether a given token value
     * was ever valid.
     */
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(HashUtil.sha256Hex(rawToken)).ifPresent(token -> {
            token.setRevoked(true);
            token.setRevokedAt(Instant.now());
            repository.save(token);
        });
    }

    private void revokeAllActiveTokensFor(User user) {
        List<RefreshToken> active = repository.findByUserAndRevokedFalse(user);
        Instant now = Instant.now();
        for (RefreshToken token : active) {
            token.setRevoked(true);
            token.setRevokedAt(now);
        }
        repository.saveAll(active);
    }

    private String generateOpaqueToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
