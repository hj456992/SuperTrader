package com.supertrader.demo.strategy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module 8 strategy-library API — local strategy RESEARCH metadata + the
 * immutable version history.
 *
 * <p>Contract (ALL inputs are validated server-side; the workspace, the
 * current actor, the strategy id, the status, the currentVersion and every
 * timestamp are ALWAYS derived from server state, never from the request
 * body; NO credential, account or trading field is ever accepted or returned;
 * the store NEVER runs / backtests / signals from a strategy and NEVER calls
 * the diagnosis / probe / AgentScope services):
 * <pre>
 *   GET   /api/v1/strategies                       current-workspace strategies
 *   POST  /api/v1/strategies                       create a strategy + version 1 (201)
 *   GET   /api/v1/strategies/{id}                  fetch one strategy
 *   PATCH /api/v1/strategies/{id}                  rename a strategy
 *   GET   /api/v1/strategies/{id}/versions         the immutable version history
 *   POST  /api/v1/strategies/{id}/versions         append an immutable version (201)
 *   POST  /api/v1/strategies/{id}/archive          archive a strategy
 * </pre>
 *
 * <p>RBAC (server-enforced): reading (list / get / versions) is allowed for
 * OWNER / ADMIN / TRADER / VIEWER; creating a strategy and appending a
 * version require OWNER / ADMIN / TRADER; renaming and archiving require
 * OWNER / ADMIN. There is deliberately NO DELETE route and NO run /
 * execute / backtest / signal / publish / approve / reject / bind-account /
 * order / cancel / recover / auto-trade route: this library only manages
 * research definitions.
 */
@RestController
@RequestMapping("/api/v1/strategies")
public class StrategyController {

    private final StrategyStore store;

    public StrategyController(StrategyStore store) {
        this.store = store;
    }

    @GetMapping
    public StrategyDtos.StrategiesResponse list() {
        return new StrategyDtos.StrategiesResponse(
                "strategies.list.v1", store.currentWorkspaceId(), store.list());
    }

    @PostMapping
    public ResponseEntity<StrategyDtos.StrategyResponse> create(
            @RequestBody(required = false) StrategyDtos.CreateRequest body) {
        StrategyDtos.StrategyResponse created = store.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public StrategyDtos.StrategyResponse get(@PathVariable("id") String id) {
        // The store enforces per-workspace isolation (404 otherwise).
        return new StrategyDtos.StrategyResponse(
                "strategies.strategy.v1", store.get(id));
    }

    @PatchMapping("/{id}")
    public StrategyDtos.StrategyResponse rename(
            @PathVariable("id") String id,
            @RequestBody(required = false) StrategyDtos.RenameRequest body) {
        return store.rename(id, body == null ? null : body.name());
    }

    @GetMapping("/{id}/versions")
    public StrategyDtos.VersionsResponse versions(@PathVariable("id") String id) {
        return new StrategyDtos.VersionsResponse(
                "strategies.versions.v1", id, store.versions(id));
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<StrategyDtos.VersionResponse> appendVersion(
            @PathVariable("id") String id,
            @RequestBody(required = false) StrategyDtos.AppendVersionRequest body) {
        StrategyDtos.VersionResponse appended = store.appendVersion(id, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(appended);
    }

    @PostMapping("/{id}/archive")
    public StrategyDtos.StrategyResponse archive(@PathVariable("id") String id) {
        return store.archive(id);
    }
}
