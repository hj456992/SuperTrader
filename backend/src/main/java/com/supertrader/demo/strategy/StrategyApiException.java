package com.supertrader.demo.strategy;

import org.springframework.http.HttpStatus;

/**
 * A strategy-library API failure with an explicit HTTP status, a
 * machine-readable error code and a user-facing (Chinese) message. Mapped by
 * {@link StrategyApiErrorHandler} into the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}}.
 *
 * <p>Codes (Module 8 contract):
 * <ul>
 *   <li>400 {@code INVALID_BODY} — empty / unreadable request body;</li>
 *   <li>400 {@code INVALID_STRATEGY_NAME} — the strategy name violates the
 *       server-side rules (required, trimmed, 1..64 chars, no control
 *       characters);</li>
 *   <li>400 {@code INVALID_STRATEGY_VERSION} — version content (summary /
 *       rules) violates the server-side rules;</li>
 *   <li>400 {@code INVALID_INSTRUMENT} — the instrument list violates the
 *       server-side rules (1..20 distinct public ids, each 1..16 letters/digits);</li>
 *   <li>400 {@code INVALID_TIMEFRAME} — the timeframe is not in the server-side
 *       whitelist (TICK / 1M / 5M / 15M / 30M / 1H / 1D);</li>
 *   <li>403 {@code FORBIDDEN} — the current actor's role cannot perform the
 *       operation (the workspace + current actor are ALWAYS derived from
 *       server state, never from the request body);</li>
 *   <li>404 {@code STRATEGY_NOT_FOUND} — no such strategy in the CURRENT
 *       workspace (a strategy of another workspace is deliberately NOT found,
 *       enforcing isolation without leaking existence);</li>
 *   <li>409 {@code DUPLICATE_STRATEGY} — another strategy of the same workspace
 *       already uses the name (case-insensitive on the trimmed name; archived
 *       names stay reserved);</li>
 *   <li>409 {@code STRATEGY_ARCHIVED} — the strategy is archived, so the
 *       requested action (rename / append version) is refused;</li>
 *   <li>500 {@code STRATEGY_PERSIST_FAILED} — local file write failure
 *       (never leaks content).</li>
 * </ul>
 */
public class StrategyApiException extends RuntimeException {

    public static final String CODE_INVALID_BODY = "INVALID_BODY";
    public static final String CODE_INVALID_STRATEGY_NAME = "INVALID_STRATEGY_NAME";
    public static final String CODE_INVALID_STRATEGY_VERSION = "INVALID_STRATEGY_VERSION";
    public static final String CODE_INVALID_INSTRUMENT = "INVALID_INSTRUMENT";
    public static final String CODE_INVALID_TIMEFRAME = "INVALID_TIMEFRAME";
    public static final String CODE_FORBIDDEN = "FORBIDDEN";
    public static final String CODE_STRATEGY_NOT_FOUND = "STRATEGY_NOT_FOUND";
    public static final String CODE_DUPLICATE_STRATEGY = "DUPLICATE_STRATEGY";
    public static final String CODE_STRATEGY_ARCHIVED = "STRATEGY_ARCHIVED";
    public static final String CODE_PERSIST_FAILED = "STRATEGY_PERSIST_FAILED";

    private final HttpStatus status;
    private final String code;

    public StrategyApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static StrategyApiException invalidBody(String message) {
        return new StrategyApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_BODY, message);
    }

    static StrategyApiException invalidStrategyName(String message) {
        return new StrategyApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_STRATEGY_NAME, message);
    }

    static StrategyApiException invalidStrategyVersion(String message) {
        return new StrategyApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_STRATEGY_VERSION, message);
    }

    static StrategyApiException invalidInstrument(String message) {
        return new StrategyApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_INSTRUMENT, message);
    }

    static StrategyApiException invalidTimeframe(String message) {
        return new StrategyApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_TIMEFRAME, message);
    }

    static StrategyApiException forbidden(String message) {
        return new StrategyApiException(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, message);
    }

    static StrategyApiException strategyNotFound(String message) {
        return new StrategyApiException(HttpStatus.NOT_FOUND, CODE_STRATEGY_NOT_FOUND, message);
    }

    static StrategyApiException duplicateStrategy(String message) {
        return new StrategyApiException(HttpStatus.CONFLICT, CODE_DUPLICATE_STRATEGY, message);
    }

    static StrategyApiException strategyArchived(String message) {
        return new StrategyApiException(HttpStatus.CONFLICT, CODE_STRATEGY_ARCHIVED, message);
    }
}
