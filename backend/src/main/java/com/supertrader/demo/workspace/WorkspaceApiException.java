package com.supertrader.demo.workspace;

import org.springframework.http.HttpStatus;

/**
 * A workspace API failure with an explicit HTTP status, a machine-readable
 * error code and a user-facing (Chinese) message. Mapped by
 * {@link WorkspaceApiErrorHandler} into the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}}.
 */
public class WorkspaceApiException extends RuntimeException {

    public static final String CODE_INVALID_BODY = "INVALID_BODY";
    public static final String CODE_INVALID_NAME = "INVALID_NAME";
    public static final String CODE_INVALID_ID = "INVALID_ID";
    public static final String CODE_WORKSPACE_NOT_FOUND = "WORKSPACE_NOT_FOUND";
    public static final String CODE_DUPLICATE_NAME = "DUPLICATE_NAME";

    private final HttpStatus status;
    private final String code;

    public WorkspaceApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static WorkspaceApiException invalidBody(String message) {
        return new WorkspaceApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_BODY, message);
    }

    static WorkspaceApiException invalidName(String message) {
        return new WorkspaceApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_NAME, message);
    }

    static WorkspaceApiException invalidId(String message) {
        return new WorkspaceApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_ID, message);
    }

    static WorkspaceApiException notFound(String message) {
        return new WorkspaceApiException(HttpStatus.NOT_FOUND, CODE_WORKSPACE_NOT_FOUND, message);
    }

    static WorkspaceApiException duplicateName(String message) {
        return new WorkspaceApiException(HttpStatus.CONFLICT, CODE_DUPLICATE_NAME, message);
    }
}
