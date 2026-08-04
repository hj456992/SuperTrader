package com.supertrader.demo.strategy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request/response DTOs for the Module 8 strategy-library API.
 *
 * <p>Every DTO carries ONLY strategy research metadata — there is no
 * credential, token, account, order/cancel field or any trading field anywhere
 * in this API. No DTO accepts a strategy id, workspaceId, status,
 * currentVersion, createdByMemberId or any timestamp: the server ALWAYS
 * derives the workspace, the actor and every server-owned field from its own
 * state and NEVER trusts a client-supplied value. A version request can only
 * carry the research definition (summary / instruments / timeframe /
 * entryRules / exitRules / riskNotes) — the version number is computed by the
 * server (max+1), never supplied by the client.
 */
public final class StrategyDtos {

    private StrategyDtos() {}

    /** The research definition part shared by the create + append-version
     *  requests. All fields are server-validated; none of them is a credential
     *  or a trading field. */
    public record VersionDefinition(
            @JsonProperty("summary") String summary,
            @JsonProperty("instruments") List<String> instruments,
            @JsonProperty("timeframe") String timeframe,
            @JsonProperty("entryRules") String entryRules,
            @JsonProperty("exitRules") String exitRules,
            @JsonProperty("riskNotes") String riskNotes) {
        @JsonCreator
        public VersionDefinition {}
    }

    /** {@code POST /api/v1/strategies} — create a strategy + its version 1. */
    public record CreateRequest(
            @JsonProperty("name") String name,
            @JsonProperty("summary") String summary,
            @JsonProperty("instruments") List<String> instruments,
            @JsonProperty("timeframe") String timeframe,
            @JsonProperty("entryRules") String entryRules,
            @JsonProperty("exitRules") String exitRules,
            @JsonProperty("riskNotes") String riskNotes) {
        @JsonCreator
        public CreateRequest {}
    }

    /** {@code PATCH /api/v1/strategies/{id}} — rename (name only). */
    public record RenameRequest(@JsonProperty("name") String name) {
        @JsonCreator
        public RenameRequest {}
    }

    /** {@code POST /api/v1/strategies/{id}/versions} — append an immutable
     *  version (the version number is server-computed, never client-supplied). */
    public record AppendVersionRequest(
            @JsonProperty("summary") String summary,
            @JsonProperty("instruments") List<String> instruments,
            @JsonProperty("timeframe") String timeframe,
            @JsonProperty("entryRules") String entryRules,
            @JsonProperty("exitRules") String exitRules,
            @JsonProperty("riskNotes") String riskNotes) {
        @JsonCreator
        public AppendVersionRequest {}
    }

    /** {@code GET /api/v1/strategies} — strategies of the CURRENT workspace. */
    public record StrategiesResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("strategies") List<Strategy> strategies) {}

    /** Single-strategy responses (create / get / rename / archive). */
    public record StrategyResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("strategy") Strategy strategy) {}

    /** {@code GET /api/v1/strategies/{id}/versions} — the immutable history. */
    public record VersionsResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("strategyId") String strategyId,
            @JsonProperty("versions") List<StrategyVersion> versions) {}

    /** {@code POST /api/v1/strategies/{id}/versions} — the appended version. */
    public record VersionResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("version") StrategyVersion version) {}

    /** Uniform error envelope. */
    public record ErrorResponse(@JsonProperty("error") ErrorBody error) {}

    public record ErrorBody(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {}
}
