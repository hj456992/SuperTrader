package com.supertrader.demo.taskcenter;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps Module 9 task-center failures to the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}} with the right HTTP status:
 * 400 (invalid body/content/name/instrument/timeframe/parameter/decision,
 * DATA_UNAVAILABLE), 403 FORBIDDEN (RBAC), 404 (SESSION/DRAFT/TASK/RUN not
 * found — a cross-workspace object is deliberately 404), 409 ILLEGAL_STATE
 * (illegal state transition) and 500 (persist / freeze failure — never leaks
 * content).
 */
@RestControllerAdvice(assignableTypes = TaskCenterController.class)
public class TaskCenterApiErrorHandler {

    @ExceptionHandler(TaskCenterApiException.class)
    public ResponseEntity<TaskCenterDtos.ErrorResponse> handle(TaskCenterApiException e) {
        return ResponseEntity.status(e.status())
                .body(new TaskCenterDtos.ErrorResponse(
                        new TaskCenterDtos.ErrorBody(e.code(), e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<TaskCenterDtos.ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new TaskCenterDtos.ErrorResponse(new TaskCenterDtos.ErrorBody(
                        TaskCenterApiException.CODE_INVALID_BODY,
                        "请求体必须是合法的 JSON 对象")));
    }
}
