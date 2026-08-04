package com.supertrader.demo.strategy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps strategy-library failures to the uniform JSON error envelope
 * {@code {"error":{"code":"...","message":"..."}}} with the right HTTP status:
 *  - 400 INVALID_BODY / INVALID_STRATEGY_NAME / INVALID_STRATEGY_VERSION /
 *    INVALID_INSTRUMENT / INVALID_TIMEFRAME — server-side input validation;
 *  - 403 FORBIDDEN — the current actor's role cannot perform the operation;
 *  - 404 STRATEGY_NOT_FOUND — unknown strategy (or a strategy of ANOTHER workspace);
 *  - 409 DUPLICATE_STRATEGY / STRATEGY_ARCHIVED — conflicts;
 *  - 500 STRATEGY_PERSIST_FAILED — local file write failure (never leaks content).
 */
@RestControllerAdvice(assignableTypes = StrategyController.class)
public class StrategyApiErrorHandler {

    @ExceptionHandler(StrategyApiException.class)
    public ResponseEntity<StrategyDtos.ErrorResponse> handle(StrategyApiException e) {
        return ResponseEntity.status(e.status())
                .body(new StrategyDtos.ErrorResponse(
                        new StrategyDtos.ErrorBody(e.code(), e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<StrategyDtos.ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new StrategyDtos.ErrorResponse(new StrategyDtos.ErrorBody(
                        StrategyApiException.CODE_INVALID_BODY,
                        "请求体必须是合法的 JSON 对象")));
    }
}
