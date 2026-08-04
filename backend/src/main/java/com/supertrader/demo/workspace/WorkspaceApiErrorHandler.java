package com.supertrader.demo.workspace;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps workspace failures to the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}} with the right HTTP status:
 *  - 400 INVALID_BODY / INVALID_NAME / INVALID_ID — server-side input validation;
 *  - 404 WORKSPACE_NOT_FOUND — unknown workspace id;
 *  - 409 DUPLICATE_NAME — duplicate (case-insensitive) workspace name;
 *  - 500 WORKSPACE_PERSIST_FAILED — local file write failure (never leaks any
 *    content, never touches credentials or trading).
 */
@RestControllerAdvice(assignableTypes = WorkspaceController.class)
public class WorkspaceApiErrorHandler {

    @ExceptionHandler(WorkspaceApiException.class)
    public ResponseEntity<WorkspaceDtos.ErrorResponse> handle(WorkspaceApiException e) {
        return ResponseEntity.status(e.status())
                .body(new WorkspaceDtos.ErrorResponse(
                        new WorkspaceDtos.ErrorBody(e.code(), e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<WorkspaceDtos.ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new WorkspaceDtos.ErrorResponse(new WorkspaceDtos.ErrorBody(
                        WorkspaceApiException.CODE_INVALID_BODY,
                        "请求体必须是合法的 JSON 对象")));
    }
}
