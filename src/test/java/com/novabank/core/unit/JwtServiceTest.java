package com.novabank.core.unit;

import com.novabank.core.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolated unit test for {@link JwtService} — no Spring context is started. The `@Value`-injected
 * fields ({@code secret}, {@code jwtExpirationMs}) are set directly via
 * {@link ReflectionTestUtils}, the standard Spring Test pattern for unit-testing a class that
 * would normally receive its configuration from the application context, without paying the
 * cost of booting one. Covers token generation, extraction, expiry validation, and the
 * {@code @PostConstruct} secret-strength guard the code review specifically credited as a
 * security strength worth having direct test coverage for.
 */
class JwtServiceTest {

    private static final String VALID_BASE64_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", VALID_BASE64_SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 86_400_000L);
    }

    @Test
    void generatesTokenThatIsValidForTheSameUser() {
        UserDetails user = testUser("alice", "ROLE_CUSTOMER");

        String token = jwtService.generateToken(user);

        assertThat(token).isNotBlank();
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtService.isTokenValid(token, user)).isTrue();
    }

    @Test
    void tokenIsNotValidForADifferentUser() {
        UserDetails alice = testUser("alice", "ROLE_CUSTOMER");
        UserDetails bob = testUser("bob", "ROLE_CUSTOMER");

        String token = jwtService.generateToken(alice);

        assertThat(jwtService.isTokenValid(token, bob)).isFalse();
    }

    @Test
    void expiredTokenFailsValidationByThrowingRatherThanReturningFalse() {
        // Documents actual current behavior rather than assumed behavior: isTokenValid() calls
        // extractUsername() first, which fully parses the token including its "exp" claim, so
        // the underlying JJWT library throws ExpiredJwtException before isTokenValid()'s own
        // isTokenExpired() check is ever reached. This is safe in practice only because the
        // sole caller, JwtAuthFilter, wraps extractUsername() in a broad try/catch and treats
        // any exception as "unauthenticated" — but it means isTokenValid() does not, by itself,
        // return false for an expired token the way its name implies; it throws instead.
        UserDetails user = testUser("alice", "ROLE_CUSTOMER");
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", -1000L);

        String token = jwtService.generateToken(user);

        assertThatThrownBy(() -> jwtService.isTokenValid(token, user))
                .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    void validateSecretRejectsASecretShorterThan32Bytes() {
        JwtService serviceWithWeakSecret = new JwtService();
        // "dGVzdA==" decodes to "test" — 4 bytes, well under the 32-byte HS256 minimum.
        ReflectionTestUtils.setField(serviceWithWeakSecret, "secret", "dGVzdA==");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(serviceWithWeakSecret, "validateSecret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void validateSecretRejectsNonBase64Secret() {
        JwtService serviceWithInvalidSecret = new JwtService();
        ReflectionTestUtils.setField(serviceWithInvalidSecret, "secret", "not-valid-base64!!!");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(serviceWithInvalidSecret, "validateSecret"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateSecretAcceptsAValid32BytePlusSecret() {
        JwtService serviceWithValidSecret = new JwtService();
        ReflectionTestUtils.setField(serviceWithValidSecret, "secret", VALID_BASE64_SECRET);

        // Should not throw.
        ReflectionTestUtils.invokeMethod(serviceWithValidSecret, "validateSecret");
    }

    private UserDetails testUser(String username, String role) {
        return new User(username, "irrelevant-password-hash", List.of(() -> role));
    }
}
