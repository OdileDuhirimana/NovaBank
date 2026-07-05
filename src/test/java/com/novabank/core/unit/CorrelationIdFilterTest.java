package com.novabank.core.unit;

import com.novabank.core.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated unit test for {@link CorrelationIdFilter} — no Spring context, using Spring's mock
 * servlet API directly. Regression-tests the three behaviors the Observability fix depends on:
 * a correlation ID is always generated (or honored, if supplied), it is echoed back to the
 * client, and it is always cleared from MDC afterward so it can never leak into an unrelated
 * later request handled by the same worker thread.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesACorrelationIdWhenNoneIsSupplied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String header = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(header).isNotBlank();
    }

    @Test
    void honorsAClientSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-supplied-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("client-supplied-id-123");
    }

    @Test
    void populatesMdcDuringTheRequestAndClearsItAfterward() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        StringBuilder observedDuringRequest = new StringBuilder();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                observedDuringRequest.append(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(observedDuringRequest.toString()).isNotBlank();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                .as("MDC must be cleared after the request completes to avoid leaking into a later request on the same thread")
                .isNull();
    }
}
