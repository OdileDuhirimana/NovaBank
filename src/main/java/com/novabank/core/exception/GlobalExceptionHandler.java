package com.novabank.core.exception;

import com.novabank.core.dto.common.ErrorResponse;
import com.novabank.core.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> fields.put(err.getField(), err.getDefaultMessage()));
        details.put("fields", fields);
        ErrorResponse body = ErrorResponse.builder()
                .code("VALIDATION_ERROR")
                .message("Request validation failed")
                .details(details)
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        ErrorResponse body = ErrorResponse.builder()
                .code("BAD_CREDENTIALS")
                .message("Invalid username or password")
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurity(SecurityException ex) {
        ErrorResponse body = ErrorResponse.builder()
                .code("FORBIDDEN")
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorResponse> handleAccessDenied(Exception ex) {
        ErrorResponse body = ErrorResponse.builder()
                .code("FORBIDDEN")
                .message("Access denied")
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex) {
        ErrorResponse body = ErrorResponse.builder()
                .code("BAD_REQUEST")
                .message(ex.getMessage())
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Raised by Hibernate's @Version check when two concurrent requests both attempt to update
     * the same Account row (e.g. two simultaneous transfers touching the same source account).
     * Mapped to 409 CONFLICT rather than a generic 500 so a client can safely retry the request
     * — the losing transaction's write was rejected specifically to prevent a lost update, it
     * did not partially apply.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(OptimisticLockingFailureException ex) {
        ErrorResponse body = ErrorResponse.builder()
                .code("CONCURRENT_UPDATE_CONFLICT")
                .message("The account was updated concurrently by another request. Please retry.")
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        // Reuse the request-scoped correlation ID CorrelationIdFilter already put into MDC
        // (and already returned to the client via the X-Correlation-Id response header) rather
        // than minting a second, unrelated ID here — this is what lets an operator paste the ID
        // from this error response directly into a log search and find every log line for the
        // request, not just this one handler's log line.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);
        ErrorResponse body = ErrorResponse.builder()
                .code("INTERNAL_ERROR")
                .message("Unexpected error. Reference: " + correlationId)
                .timestamp(OffsetDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
