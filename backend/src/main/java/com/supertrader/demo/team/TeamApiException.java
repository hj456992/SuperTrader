package com.supertrader.demo.team;

import org.springframework.http.HttpStatus;

/**
 * A team API failure with an explicit HTTP status, a machine-readable error
 * code and a user-facing (Chinese) message. Mapped by
 * {@link TeamApiErrorHandler} into the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}}.
 *
 * Codes (Module 6 contract):
 *  400 INVALID_BODY / INVALID_NAME / INVALID_ROLE — server-side input validation;
 *  403 FORBIDDEN — the current actor's role cannot perform the operation;
 *  404 MEMBER_NOT_FOUND — no such member in the CURRENT workspace (a member of
 *      another workspace is deliberately NOT found, enforcing isolation);
 *  409 DUPLICATE_MEMBER — same display name in the same workspace;
 *  409 LAST_OWNER_PROTECTED — the change would deactivate or demote the last
 *      active OWNER of the workspace;
 *  409 INACTIVE_MEMBER — the actor-switch target is currently inactive;
 *  500 TEAM_PERSIST_FAILED — local file write failure (never leaks content).
 */
public class TeamApiException extends RuntimeException {

    public static final String CODE_INVALID_BODY = "INVALID_BODY";
    public static final String CODE_INVALID_NAME = "INVALID_NAME";
    public static final String CODE_INVALID_ROLE = "INVALID_ROLE";
    public static final String CODE_FORBIDDEN = "FORBIDDEN";
    public static final String CODE_MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
    public static final String CODE_DUPLICATE_MEMBER = "DUPLICATE_MEMBER";
    public static final String CODE_LAST_OWNER_PROTECTED = "LAST_OWNER_PROTECTED";
    public static final String CODE_INACTIVE_MEMBER = "INACTIVE_MEMBER";
    public static final String CODE_PERSIST_FAILED = "TEAM_PERSIST_FAILED";

    private final HttpStatus status;
    private final String code;

    public TeamApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static TeamApiException invalidBody(String message) {
        return new TeamApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_BODY, message);
    }

    static TeamApiException invalidName(String message) {
        return new TeamApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_NAME, message);
    }

    static TeamApiException invalidRole(String message) {
        return new TeamApiException(HttpStatus.BAD_REQUEST, CODE_INVALID_ROLE, message);
    }

    static TeamApiException forbidden(String message) {
        return new TeamApiException(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, message);
    }

    static TeamApiException memberNotFound(String message) {
        return new TeamApiException(HttpStatus.NOT_FOUND, CODE_MEMBER_NOT_FOUND, message);
    }

    static TeamApiException duplicateMember(String message) {
        return new TeamApiException(HttpStatus.CONFLICT, CODE_DUPLICATE_MEMBER, message);
    }

    static TeamApiException lastOwnerProtected(String message) {
        return new TeamApiException(HttpStatus.CONFLICT, CODE_LAST_OWNER_PROTECTED, message);
    }

    static TeamApiException inactiveMember(String message) {
        return new TeamApiException(HttpStatus.CONFLICT, CODE_INACTIVE_MEMBER, message);
    }
}
