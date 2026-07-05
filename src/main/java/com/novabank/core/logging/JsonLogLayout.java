package com.novabank.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;
import java.util.Map;

/**
 * Hand-rolled, single-line JSON log layout for Logback.
 *
 * WHY A CUSTOM LAYOUT INSTEAD OF logstash-logback-encoder: that library is the conventional
 * choice for structured JSON logging in a Spring Boot app, but it is an additional third-party
 * dependency this project's dependency list was not pre-approved to add. Logback's core module
 * (already on the classpath via {@code spring-boot-starter-web}/{@code -security}, which pull in
 * {@code logback-classic}) exposes exactly the extension point needed — {@link LayoutBase} — to
 * produce real, valid, single-line-per-event JSON without adding a new dependency. This is a
 * deliberate build-vs-buy tradeoff: a production system logging at high volume would likely
 * prefer the battle-tested library (richer field support, better performance tuning knobs); at
 * this project's scale, ~80 lines of straightforward JSON formatting is a reasonable substitute
 * that keeps the dependency surface exactly as curated.
 *
 * Wired in via {@code logback-spring.xml}, active only under the {@code staging}/{@code prod}
 * Spring profiles (see that file's {@code <springProfile>} blocks) — local/dev output stays
 * human-readable plain text, since JSON console output is a genuine readability regression for
 * a developer tailing logs interactively.
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    @Override
    public String doLayout(ILoggingEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendField(json, "timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        appendField(json, "level", event.getLevel().toString());
        appendField(json, "logger", event.getLoggerName());
        appendField(json, "thread", event.getThreadName());
        appendField(json, "message", event.getFormattedMessage());

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            appendIfPresent(json, "correlationId", mdc.get("correlationId"));
            // Populated automatically by Micrometer Tracing (Brave bridge) when tracing is
            // active — see application.yml management.tracing.* — allowing a JSON log line to
            // be correlated with a distributed trace span, not just the request-scoped
            // correlation ID above.
            appendIfPresent(json, "traceId", mdc.get("traceId"));
            appendIfPresent(json, "spanId", mdc.get("spanId"));
        }

        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            appendField(json, "exception", ThrowableProxyUtil.asString(throwableProxy));
        }

        // Remove the trailing comma left by the last appendField call, then close the object.
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        json.append('}').append(System.lineSeparator());
        return json.toString();
    }

    private void appendIfPresent(StringBuilder json, String key, String value) {
        if (value != null && !value.isBlank()) {
            appendField(json, key, value);
        }
    }

    private void appendField(StringBuilder json, String key, String value) {
        json.append('"').append(key).append("\":");
        json.append('"').append(escape(value)).append('"');
        json.append(',');
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
