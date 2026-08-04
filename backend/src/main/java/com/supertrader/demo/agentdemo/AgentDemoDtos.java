package com.supertrader.demo.agentdemo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * The Agent Demo data-transfer contracts (Task 1).
 *
 * <p>These mirror the Stable Demo Contract verbatim. Every request DTO is
 * <b>strict</b> ({@code ignoreUnknown = false}): the browser may submit ONLY
 * the documented fields. Server-owned concerns — workspace, role, model, base
 * URL, system prompt, budget, capability overrides and any secret — are NEVER
 * accepted from a client; attempting to send one makes deserialization fail
 * (fail-closed) so the value can never reach a run.
 *
 * <p>No hidden chain-of-thought, raw prompt or credential is ever present in
 * any response DTO.
 */
public final class AgentDemoDtos {

    private AgentDemoDtos() {}

    /** Strict request: the client may only send a (nullable) title. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateSessionRequest(@JsonProperty("title") String title) {}

    /** Strict request: the client may only send the message content. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateTurnRequest(@JsonProperty("content") String content) {}

    /** The 202 returned by {@code POST /turns} once the turn is durably accepted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AcceptedTurnResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("turnId") String turnId,
            @JsonProperty("runId") String runId,
            @JsonProperty("status") String status,
            @JsonProperty("eventSeq") long eventSeq) {

        public static final String SCHEMA = "agent-demo.accepted.v1";

        /** Canonical constructor with the fixed schema. */
        public AcceptedTurnResponse(String sessionId, String turnId, String runId,
                                    String status, long eventSeq) {
            this(SCHEMA, sessionId, turnId, runId, status, eventSeq);
        }
    }

    /** The body of {@code POST /runs/{runId}/stop}. */
    public record StopResponse(
            @JsonProperty("runId") String runId,
            @JsonProperty("status") String status,
            @JsonProperty("stepsAtStop") int stepsAtStop) {}

    /** Strict request: the client may only send the decision string. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SeedDecisionRequest(@JsonProperty("decision") String decision) {}

    /** Strict request: a Draft field patch (server owns version via If-Match). */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftPatchRequest(
            @JsonProperty("field") String field,
            @JsonProperty("value") String value,
            @JsonProperty("evidenceExcerpt") String evidenceExcerpt) {}

    /** The SSE / event-outbox envelope. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoEventEnvelope(
            @JsonProperty("schema") String schema,
            @JsonProperty("seq") long seq,
            @JsonProperty("eventId") String eventId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("runId") String runId,
            @JsonProperty("type") String type,
            @JsonProperty("at") String at,
            @JsonProperty("payload") Map<String, Object> payload) {

        public static final String SCHEMA = "agent-demo.event.v1";

        /** Canonical constructor with the fixed schema. */
        public DemoEventEnvelope(long seq, String eventId, String sessionId, String runId,
                                 String type, String at, Map<String, Object> payload) {
            this(SCHEMA, seq, eventId, sessionId, runId, type, at, payload);
        }
    }

    /** One traced run shown in the Harness Inspector. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoRunView(
            @JsonProperty("id") String id,
            @JsonProperty("intent") String intent,
            @JsonProperty("intentLabels") List<String> intentLabels,
            @JsonProperty("authorization") String authorization,
            @JsonProperty("calibratedConfidence") Double calibratedConfidence,
            @JsonProperty("requiresConfirmation") Boolean requiresConfirmation,
            @JsonProperty("status") String status,
            @JsonProperty("stepsUsed") int stepsUsed,
            @JsonProperty("maxSteps") int maxSteps,
            @JsonProperty("toolCallsUsed") int toolCallsUsed,
            @JsonProperty("maxToolCalls") int maxToolCalls,
            @JsonProperty("modelCallsUsed") int modelCallsUsed,
            @JsonProperty("maxModelCalls") int maxModelCalls,
            @JsonProperty("outputCharsUsed") int outputCharsUsed,
            @JsonProperty("elapsedMs") long elapsedMs,
            @JsonProperty("modelUnavailable") boolean modelUnavailable,
            @JsonProperty("error") String error,
            @JsonProperty("checkpointReason") String checkpointReason,
            @JsonProperty("createdAt") String createdAt) {}

    /** One run step shown in the capability timeline. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoStepView(
            @JsonProperty("runId") String runId,
            @JsonProperty("seq") int seq,
            @JsonProperty("kind") String kind,
            @JsonProperty("capability") String capability,
            @JsonProperty("summary") String summary,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("createdAt") String createdAt) {}

    /** A clearly-labeled Mock evidence entry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoEvidenceView(
            @JsonProperty("fieldPath") String fieldPath,
            @JsonProperty("sourceType") String sourceType,
            @JsonProperty("sourceId") String sourceId,
            @JsonProperty("excerpt") String excerpt,
            @JsonProperty("productionEvidence") boolean productionEvidence) {}

    /** A StrategySeed shown in the conversation pane. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoSeedView(
            @JsonProperty("id") String id,
            @JsonProperty("summary") String summary,
            @JsonProperty("status") String status,
            @JsonProperty("sourceTurnId") String sourceTurnId) {}

    /** A StrategySpecDraft shown in the artifact card (never executable). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoDraftView(
            @JsonProperty("id") String id,
            @JsonProperty("status") String status,
            @JsonProperty("version") int version,
            @JsonProperty("completeness") int completeness,
            @JsonProperty("missingFields") List<String> missingFields,
            @JsonProperty("fields") Map<String, Object> fields,
            @JsonProperty("evidence") List<DemoEvidenceView> evidence,
            @JsonProperty("nextQuestion") String nextQuestion,
            @JsonProperty("frozen") boolean frozen) {}

    /** A conversation turn shown in the chat history. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoTurnView(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("intent") String intent,
            @JsonProperty("modelUnavailable") boolean modelUnavailable,
            @JsonProperty("seed") DemoSeedView seed,
            @JsonProperty("createdAt") String createdAt) {}

    /** The full session detail returned by {@code GET /sessions/{id}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoSessionDetail(
            @JsonProperty("id") String id,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("title") String title,
            @JsonProperty("status") String status,
            @JsonProperty("turns") List<DemoTurnView> turns,
            @JsonProperty("runs") List<DemoRunView> runs,
            @JsonProperty("stepsByRun") List<DemoStepView> steps,
            @JsonProperty("seeds") List<DemoSeedView> seeds,
            @JsonProperty("activeDraft") DemoDraftView activeDraft,
            @JsonProperty("lastEventSeq") long lastEventSeq) {}

    /** A summary item in {@code GET /sessions}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DemoSessionSummary(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("status") String status,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("lastEventSeq") long lastEventSeq) {}

    /** The uniform error envelope. */
    public record ErrorResponse(@JsonProperty("error") ErrorBody error) {
        public ErrorResponse(String code, String message) {
            this(new ErrorBody(code, message));
        }
    }

    public record ErrorBody(@JsonProperty("code") String code,
                            @JsonProperty("message") String message) {}
}
