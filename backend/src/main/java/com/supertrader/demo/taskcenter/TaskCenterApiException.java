package com.supertrader.demo.taskcenter;

import org.springframework.http.HttpStatus;

/**
 * A Module 9 task-center API failure with an explicit HTTP status, a
 * machine-readable error code and a user-facing (Chinese) message. Mapped by
 * {@link TaskCenterApiErrorHandler} into the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}}.
 *
 * <p>Codes:
 * <ul>
 *   <li>400 INVALID_BODY / INVALID_CONTENT / INVALID_NAME / INVALID_INSTRUMENT /
 *       INVALID_TIMEFRAME / INVALID_PARAMETER / INVALID_DECISION /
 *       DATA_UNAVAILABLE — input validation;</li>
 *   <li>403 FORBIDDEN — the current actor's role cannot perform the operation
 *       (workspace + actor always derived from server state);</li>
 *   <li>404 SESSION_NOT_FOUND / DRAFT_NOT_FOUND / TASK_NOT_FOUND /
 *       RUN_NOT_FOUND — unknown object (or an object of ANOTHER workspace);</li>
 *   <li>409 ILLEGAL_STATE — an illegal state transition (e.g. starting an
 *       unapproved task, submitting an unvalidated draft);</li>
 *   <li>500 TASK_CENTER_PERSIST_FAILED / FREEZE_FAILED — persistence or
 *       freeze-transaction failure (never leaks content).</li>
 * </ul>
 */
public class TaskCenterApiException extends RuntimeException {

    public static final String CODE_INVALID_BODY = "INVALID_BODY";
    public static final String CODE_INVALID_CONTENT = "INVALID_CONTENT";
    public static final String CODE_INVALID_NAME = "INVALID_NAME";
    public static final String CODE_INVALID_INSTRUMENT = "INVALID_INSTRUMENT";
    public static final String CODE_INVALID_TIMEFRAME = "INVALID_TIMEFRAME";
    public static final String CODE_INVALID_PARAMETER = "INVALID_PARAMETER";
    public static final String CODE_INVALID_DECISION = "INVALID_DECISION";
    public static final String CODE_DATA_UNAVAILABLE = "DATA_UNAVAILABLE";
    public static final String CODE_EVIDENCE_MISSING = "EVIDENCE_MISSING";
    public static final String CODE_MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String CODE_FORBIDDEN = "FORBIDDEN";
    public static final String CODE_SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    public static final String CODE_TURN_NOT_FOUND = "TURN_NOT_FOUND";
    public static final String CODE_DRAFT_NOT_FOUND = "DRAFT_NOT_FOUND";
    public static final String CODE_TASK_NOT_FOUND = "TASK_NOT_FOUND";
    public static final String CODE_RUN_NOT_FOUND = "RUN_NOT_FOUND";
    public static final String CODE_ILLEGAL_STATE = "ILLEGAL_STATE";
    public static final String CODE_PERSIST_FAILED = "TASK_CENTER_PERSIST_FAILED";
    public static final String CODE_FREEZE_FAILED = "FREEZE_FAILED";

    private final HttpStatus status;
    private final String code;

    public TaskCenterApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static TaskCenterApiException invalidBody(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_BODY, message);
    }

    static TaskCenterApiException invalidContent(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_CONTENT, message);
    }

    static TaskCenterApiException invalidName(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_NAME, message);
    }

    static TaskCenterApiException invalidInstrument(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_INSTRUMENT, message);
    }

    static TaskCenterApiException invalidTimeframe(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_TIMEFRAME, message);
    }

    static TaskCenterApiException invalidParameter(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_PARAMETER, message);
    }

    static TaskCenterApiException invalidDecision(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_DECISION, message);
    }

    static TaskCenterApiException dataUnavailable(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_DATA_UNAVAILABLE, message);
    }

    static TaskCenterApiException evidenceMissing(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_EVIDENCE_MISSING, message);
    }

    static TaskCenterApiException modelUnavailable(String message) {
        return new TaskCenterApiException(HttpStatus.BAD_REQUEST, CODE_MODEL_UNAVAILABLE, message);
    }

    static TaskCenterApiException forbidden(String message) {
        return new TaskCenterApiException(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, message);
    }

    static TaskCenterApiException sessionNotFound(String message) {
        return new TaskCenterApiException(HttpStatus.NOT_FOUND, CODE_SESSION_NOT_FOUND, message);
    }

    static TaskCenterApiException turnNotFound(String message) {
        return new TaskCenterApiException(HttpStatus.NOT_FOUND, CODE_TURN_NOT_FOUND, message);
    }

    static TaskCenterApiException draftNotFound(String message) {
        return new TaskCenterApiException(HttpStatus.NOT_FOUND, CODE_DRAFT_NOT_FOUND, message);
    }

    static TaskCenterApiException taskNotFound(String message) {
        return new TaskCenterApiException(HttpStatus.NOT_FOUND, CODE_TASK_NOT_FOUND, message);
    }

    static TaskCenterApiException runNotFound(String message) {
        return new TaskCenterApiException(HttpStatus.NOT_FOUND, CODE_RUN_NOT_FOUND, message);
    }

    /** An illegal state transition — the resource exists but cannot move this way. */
    static TaskCenterApiException illegalState(String message) {
        return new TaskCenterApiException(HttpStatus.CONFLICT, CODE_ILLEGAL_STATE, message);
    }
}
