package com.supertrader.demo.agentdemo;

import com.supertrader.demo.agentdemo.AgentDemoDtos.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The Agent Demo error handler (Task 8).
 *
 * <p>Maps typed {@link AgentDemoApiException}s and framework errors to the
 * uniform {@code {schema, error: {code, message}}} envelope with stable error
 * codes (design §10.2). Error messages NEVER echo the offending raw user
 * content, never reveal a credential, and never expose a hidden chain.
 */
@RestControllerAdvice(assignableTypes = AgentDemoController.class)
public class AgentDemoErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoErrorHandler.class);

    @ExceptionHandler(AgentDemoApiException.class)
    public ResponseEntity<ErrorResponse> handleDemo(AgentDemoApiException ex) {
        HttpStatus status = statusFor(ex.code());
        return ResponseEntity.status(status).body(new ErrorResponse(ex.code(), safeMessage(ex)));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("IDEMPOTENCY_KEY_REQUIRED",
                            "Idempotency-Key header is required"));
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST",
                "missing required header"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // Strict DTO failures (unknown client-supplied fields) land here.
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST",
                "request body is invalid or contains unknown fields"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        // Never leak internals; log server-side only.
        log.warn("agent-demo unexpected error: {}", ex.toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ErrorResponse("PERSISTENCE_UNAVAILABLE",
                        "unexpected error; please retry"));
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT", "OPTIMISTIC_LOCK_CONFLICT", "INVALID_STATE",
                 "SESSION_BUSY", "ALREADY_ACTIVE" -> HttpStatus.CONFLICT;
            case "RUN_QUEUE_FULL" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "MODEL_UNAVAILABLE", "MODEL_TIMEOUT", "TOOL_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            // Default (validation / sensitive / idempotency-key / bad request): 400.
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String safeMessage(AgentDemoApiException ex) {
        // The message is already a generic, echo-free reason from the guard /
        // service. We never include the raw user content here.
        return ex.getMessage() == null ? ex.code() : ex.getMessage();
    }
}
