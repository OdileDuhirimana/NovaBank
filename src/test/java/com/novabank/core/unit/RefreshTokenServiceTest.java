package com.novabank.core.unit;

import com.novabank.core.model.RefreshToken;
import com.novabank.core.model.User;
import com.novabank.core.repository.RefreshTokenRepository;
import com.novabank.core.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit test for {@link RefreshTokenService} — no Spring context, no database.
 * Regression-tests the three security-relevant contracts: tokens are stored only as a hash
 * (never in a form that would let a test simply compare the raw value against a stored field),
 * a valid token rotates cleanly, and reuse of an already-revoked token triggers a full
 * revoke-all-sessions response rather than being silently accepted or merely rejected.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository repository;

    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repository);
        ReflectionTestUtils.setField(service, "refreshExpirationMs", 604_800_000L);
        user = new User();
        user.setUsername("alice");
    }

    @Test
    void issueStoresOnlyAHashOfTheRawToken() {
        RefreshTokenService.IssuedRefreshToken issued = service.issue(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(saved.getTokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(saved.getTokenHash()).hasSize(64); // hex-encoded SHA-256
        assertThat(saved.isRevoked()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void rotateRevokesThePresentedTokenAndIssuesADifferentOne() {
        RefreshToken stored = activeTokenFor(user, "raw-token-value", Instant.now().plusSeconds(3600));
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        RefreshTokenService.RotationResult result = service.rotate("raw-token-value");

        assertThat(stored.isRevoked()).isTrue();
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(result.user()).isEqualTo(user);
        assertThat(result.issuedRefreshToken().rawToken()).isNotEqualTo("raw-token-value");
        verify(repository, times(2)).save(any(RefreshToken.class)); // revoke old + save new
    }

    @Test
    void rotateRejectsAnExpiredToken() {
        RefreshToken expired = activeTokenFor(user, "raw-token-value", Instant.now().minusSeconds(1));
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate("raw-token-value"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rotateRejectsAnUnknownToken() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("never-issued"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void reusingAnAlreadyRevokedTokenRevokesAllActiveSessionsAndThrows() {
        RefreshToken alreadyUsed = activeTokenFor(user, "stolen-token", Instant.now().plusSeconds(3600));
        alreadyUsed.setRevoked(true);
        RefreshToken otherActiveSession = activeTokenFor(user, "another-session-token", Instant.now().plusSeconds(3600));

        when(repository.findByTokenHash(any())).thenReturn(Optional.of(alreadyUsed));
        when(repository.findByUserAndRevokedFalse(user)).thenReturn(List.of(otherActiveSession));

        assertThatThrownBy(() -> service.rotate("stolen-token"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("reuse detected");

        assertThat(otherActiveSession.isRevoked()).isTrue();
        verify(repository).saveAll(List.of(otherActiveSession));
    }

    @Test
    void revokeIsIdempotentForAnUnknownToken() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revoke("never-issued");

        verify(repository, never()).save(any(RefreshToken.class));
    }

    @Test
    void revokeMarksAKnownTokenAsRevoked() {
        RefreshToken stored = activeTokenFor(user, "raw-token-value", Instant.now().plusSeconds(3600));
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        service.revoke("raw-token-value");

        assertThat(stored.isRevoked()).isTrue();
        assertThat(stored.getRevokedAt()).isNotNull();
        verify(repository).save(stored);
    }

    private RefreshToken activeTokenFor(User owner, String rawTokenForHashing, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(owner);
        token.setTokenHash(com.novabank.core.common.HashUtil.sha256Hex(rawTokenForHashing));
        token.setExpiresAt(expiresAt);
        token.setRevoked(false);
        return token;
    }
}
