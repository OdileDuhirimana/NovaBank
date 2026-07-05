package com.novabank.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates a per-request correlation ID into SLF4J's MDC (Mapped Diagnostic Context) so every
 * log line emitted while handling a request — across controller, service, and filter code — can
 * be tied back to that one request, and echoes it back to the client as a response header for
 * client-side correlation with support requests.
 *
 * WHY THIS EXISTS: the code review's Observability section flagged "a correlation ID is
 * generated only at the point of an unhandled exception" as the entire extent of request
 * traceability — everything else was plain, uncorrelated text logging. This filter runs first
 * (highest precedence) among the servlet container's filter chain, ahead of Spring Security's
 * own filter chain, so the correlation ID is available for the *entire* request lifecycle,
 * including authentication/authorization decisions, not just business logic.
 *
 * If the caller supplies an {@code X-Correlation-Id} header, it is honored (useful for a client
 * or upstream gateway that wants to thread its own trace ID through); otherwise a new one is
 * generated. The MDC entry is always cleared in a {@code finally} block — MDC is thread-local and
 * servlet containers reuse worker threads across requests, so failing to clear it would leak one
 * request's correlation ID into an unrelated later request handled by the same thread.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER_NAME = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String supplied = request.getHeader(HEADER_NAME);
        return (supplied != null && !supplied.isBlank()) ? supplied.trim() : UUID.randomUUID().toString();
    }
}
