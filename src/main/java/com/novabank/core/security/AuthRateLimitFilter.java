package com.novabank.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limits the public, unauthenticated authentication endpoints (register/login) per client
 * IP address using an in-memory token-bucket (Bucket4j).
 *
 * Why this exists: the code review found `/api/auth/login` and `/api/auth/register` fully open
 * to unlimited attempts — no throttling of any kind, making credential-stuffing, brute-force
 * login, and registration-spam trivial. `FraudService.logFailedLogin()` already records every
 * failed attempt but nothing previously acted on that signal.
 *
 * Design notes / known limitations (acceptable for this portfolio's single-instance deployment
 * footprint, called out explicitly rather than silently accepted):
 * - Buckets are held in an in-process ConcurrentHashMap keyed by client IP. This is correct for
 *   a single application instance (as currently deployed via render.yaml) but does not share
 *   state across multiple instances; a horizontally-scaled deployment would need a shared store
 *   (e.g. Redis-backed Bucket4j) for the limit to hold across instances.
 * - The client IP is read from `X-Forwarded-For` (first hop) when present, falling back to
 *   `HttpServletRequest.getRemoteAddr()`. This is standard practice behind a reverse proxy
 *   (Render, most PaaS) but can be spoofed by a client if the app is ever exposed directly
 *   without a trusted proxy in front of it stripping/overwriting that header.
 * - This limits by IP, not by attempted username, so it does not fully prevent a distributed
 *   credential-stuffing attack from many IPs against one account; combining this with the
 *   existing FraudService failed-login logging (for a future account-lockout policy) would
 *   close that gap, but a full lockout policy is out of scope for this pass.
 *
 * Limit: 20 requests per client IP per minute across register+login combined. This is
 * intentionally generous enough to never affect a legitimate user retrying a mistyped password
 * a few times, while still making sustained brute-force/credential-stuffing (typically hundreds
 * to thousands of attempts) meaningfully slower.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final int CAPACITY = 20;
    private static final Duration REFILL_PERIOD = Duration.ofMinutes(1);

    private final Map<String, Bucket> bucketsByClientIp = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    // Disabled by default only in the test profile (see src/test/resources/application.yml) so
    // that the many unrelated integration tests exercising /api/auth/register and
    // /api/auth/login (all sharing MockMvc's fixed 127.0.0.1 remote address, and sharing this
    // filter's in-memory bucket state across the whole Spring TestContext-cached suite run) do
    // not spuriously trip each other's rate limit. The dedicated AuthRateLimitTests suite
    // re-enables this filter explicitly via @TestPropertySource to verify the real behavior.
    @Value("${app.security.auth-rate-limit.enabled:true}")
    private boolean enabled;

    public AuthRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = request.getRequestURI();
        boolean isRateLimitedAuthEndpoint = "POST".equalsIgnoreCase(request.getMethod())
                && ("/api/v1/auth/login".equals(path) || "/api/v1/auth/register".equals(path)
                        || "/api/v1/auth/refresh".equals(path));
        return !isRateLimitedAuthEndpoint;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String clientIp = resolveClientIp(request);
        Bucket bucket = bucketsByClientIp.computeIfAbsent(clientIp, ip -> newBucket());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        Map<String, Object> body = Map.of(
                "code", "RATE_LIMIT_EXCEEDED",
                "message", "Too many authentication attempts. Please wait before retrying.",
                "timestamp", java.time.OffsetDateTime.now().toString()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(CAPACITY, io.github.bucket4j.Refill.greedy(CAPACITY, REFILL_PERIOD));
        return Bucket.builder().addLimit(limit).build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
