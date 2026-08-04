package com.supertrader.demo.team;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps team failures to the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}} with the right HTTP status:
 *  - 400 INVALID_BODY / INVALID_NAME / INVALID_ROLE — server-side input validation;
 *  - 403 FORBIDDEN — the current actor's role cannot perform the operation;
 *  - 404 MEMBER_NOT_FOUND — unknown member (or a member of ANOTHER workspace);
 *  - 409 DUPLICATE_MEMBER / LAST_OWNER_PROTECTED / INACTIVE_MEMBER — conflicts;
 *  - 500 TEAM_PERSIST_FAILED — local file write failure (never leaks content).
 */
@RestControllerAdvice(assignableTypes = TeamController.class)
public class TeamApiErrorHandler {

    @ExceptionHandler(TeamApiException.class)
    public ResponseEntity<TeamDtos.ErrorResponse> handle(TeamApiException e) {
        return ResponseEntity.status(e.status())
                .body(new TeamDtos.ErrorResponse(
                        new TeamDtos.ErrorBody(e.code(), e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<TeamDtos.ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new TeamDtos.ErrorResponse(new TeamDtos.ErrorBody(
                        TeamApiException.CODE_INVALID_BODY,
                        "请求体必须是合法的 JSON 对象")));
    }
}
