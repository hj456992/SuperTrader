package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request/response DTOs for the Module 9 task-center API.
 *
 * <p>Every DTO carries ONLY the whitelisted task-center fields — there is no
 * credential, token, account, order/cancel field or any trading field anywhere
 * in this API. No DTO accepts a workspaceId, session id, draft id, task id,
 * actor id or timestamp: the server ALWAYS derives the workspace, the actor and
 * every server-owned field from its own state and NEVER trusts a client-supplied
 * value. There is no DELETE route: cancellation is a state transition with a
 * full audit trail.
 */
public final class TaskCenterDtos {

    private TaskCenterDtos() {}

    // ------------------------------------------------------------------ //
    // Requests
    // ------------------------------------------------------------------ //

    /** {@code POST /api/v1/agent/sessions} — create a session (title only). */
    public record CreateSessionRequest(@JsonProperty("title") String title) {
        @JsonCreator
        public CreateSessionRequest {}
    }

    /** {@code POST /api/v1/agent/sessions/{id}/turns} — a user message. The
     *  content is validated server-side (trimmed, 1..4000 chars, no control
     *  characters) and credential-shaped content is rejected (400). */
    public record CreateTurnRequest(@JsonProperty("content") String content) {
        @JsonCreator
        public CreateTurnRequest {}
    }

    /** {@code POST /api/v1/agent/sessions/{id}/turns/{turnId}/seed-decision} —
     *  the user's explicit choice on a detected strategy candidate.
     *  {@code CO_CREATE} enters strategy co-creation (creates the Draft);
     *  {@code DISCUSS} keeps it as a discussion only. */
    public record SeedDecisionRequest(@JsonProperty("decision") String decision) {
        @JsonCreator
        public SeedDecisionRequest {}
    }

    /** {@code PATCH /api/v1/strategy-drafts/{id}} — a Draft field patch.
     *  Every field is optional (null = not provided); provided fields are
     *  server-validated deterministically. No client can set id / workspaceId /
     *  sessionId / status / completeness / missingFields / evidence / creator /
     *  timestamps. */
    public record DraftPatch(
            @JsonProperty("name") String name,
            @JsonProperty("instruments") List<String> instruments,
            @JsonProperty("timeframe") String timeframe,
            @JsonProperty("fastWindow") Integer fastWindow,
            @JsonProperty("slowWindow") Integer slowWindow,
            @JsonProperty("positionSize") Double positionSize,
            @JsonProperty("stopLossPct") Double stopLossPct,
            @JsonProperty("feeBps") Integer feeBps,
            @JsonProperty("slippageBps") Integer slippageBps,
            @JsonProperty("entryCondition") String entryCondition,
            @JsonProperty("exitCondition") String exitCondition,
            @JsonProperty("riskLimits") String riskLimits,
            @JsonProperty("backtestAssumptions") String backtestAssumptions,
            @JsonProperty("templateType") String templateType,
            @JsonProperty("parameters") SpecParameters parameters) {
        @JsonCreator
        public DraftPatch {}

        /** Legacy flat SMA PATCH constructor retained unchanged. */
        public DraftPatch(String name, List<String> instruments, String timeframe,
                          Integer fastWindow, Integer slowWindow, Double positionSize,
                          Double stopLossPct, Integer feeBps, Integer slippageBps,
                          String entryCondition, String exitCondition, String riskLimits,
                          String backtestAssumptions) {
            this(name, instruments, timeframe, fastWindow, slowWindow, positionSize,
                    stopLossPct, feeBps, slippageBps, entryCondition, exitCondition,
                    riskLimits, backtestAssumptions, null, null);
        }
    }

    /** Approval / rejection reason (reason only; the decision is in the URL). */
    public record ReasonRequest(@JsonProperty("reason") String reason) {
        @JsonCreator
        public ReasonRequest {}
    }

    /** {@code POST /api/v1/tasks/backtests} — create a BACKTEST task from a
     *  FROZEN, validated Draft. datasetId is optional (defaults to the first
     *  dataset covering the snapshot's instrument + timeframe). */
    public record CreateTaskRequest(
            @JsonProperty("draftId") String draftId,
            @JsonProperty("datasetId") String datasetId) {
        @JsonCreator
        public CreateTaskRequest {}
    }

    /** {@code POST /api/v1/tasks/{id}/cancel} — local cancellation reason. */
    public record CancelRequest(@JsonProperty("reason") String reason) {
        @JsonCreator
        public CancelRequest {}
    }

    // ------------------------------------------------------------------ //
    // Responses
    // ------------------------------------------------------------------ //

    public record SessionsResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("sessions") List<ConversationSession> sessions) {}

    public record SessionResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("session") ConversationSession session) {}

    public record SessionDetailResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("session") ConversationSession session,
            @JsonProperty("turns") List<ConversationTurn> turns,
            @JsonProperty("drafts") List<StrategySpecDraft> drafts,
            @JsonProperty("tasks") List<AgentTask> tasks) {}

    public record TurnResultResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("userTurn") ConversationTurn userTurn,
            @JsonProperty("assistantTurn") ConversationTurn assistantTurn,
            @JsonProperty("agentRun") AgentRun agentRun,
            @JsonProperty("steps") List<AgentStep> steps,
            @JsonProperty("checkpoint") RunCheckpoint checkpoint,
            @JsonProperty("modelUnavailable") boolean modelUnavailable) {}

    public record SeedDecisionResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("seed") StrategySeed seed,
            @JsonProperty("draft") StrategySpecDraft draft) {}

    public record DraftResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("draft") StrategySpecDraft draft,
            @JsonProperty("latestReport") ValidationReport latestReport,
            @JsonProperty("snapshot") StrategySpecSnapshot snapshot,
            @JsonProperty("approvals") List<ApprovalRecord> approvals) {}

    public record ValidationResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("draft") StrategySpecDraft draft,
            @JsonProperty("report") ValidationReport report) {}

    public record TasksResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("tasks") List<AgentTask> tasks) {}

    public record TaskResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("task") AgentTask task,
            @JsonProperty("approvals") List<ApprovalRecord> approvals,
            @JsonProperty("runs") List<BacktestRun> runs) {}

    public record TaskStartResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("task") AgentTask task,
            @JsonProperty("run") BacktestRun run) {}

    public record AgentRunResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("run") AgentRun run,
            @JsonProperty("steps") List<AgentStep> steps,
            @JsonProperty("checkpoint") RunCheckpoint checkpoint) {}

    public record BacktestRunResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("run") BacktestRun run) {}

    public record TaskAuditResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("approvals") List<ApprovalRecord> approvals,
            @JsonProperty("auditEvents") List<AuditEvent> auditEvents) {}

    /** Uniform error envelope. */
    public record ErrorResponse(@JsonProperty("error") ErrorBody error) {}

    public record ErrorBody(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {}
}
