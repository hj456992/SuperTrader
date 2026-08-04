package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One controlled Agent Runtime Harness run (Module 9).
 *
 * <p>Every user turn is processed inside an AgentRun with an explicit budget
 * ({@link RunBudget}: max steps, max tool calls, max output chars). When the
 * budget is exhausted a {@link RunCheckpoint} is saved and the run ends
 * (CHECKPOINTED) — it can never loop forever. {@code modelUnavailable} is true
 * when the reply was generated WITHOUT a model (MODEL_UNAVAILABLE): the
 * backend still starts, the deterministic Validator and Backtest Runner still
 * work, and the conversation clearly shows MODEL_UNAVAILABLE — model answers
 * are never faked.
 */
public record AgentRun(
        @JsonProperty("id") String id,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("intent") String intent,
        @JsonProperty("status") String status,
        @JsonProperty("budget") RunBudget budget,
        @JsonProperty("stepsUsed") int stepsUsed,
        @JsonProperty("toolCallsUsed") int toolCallsUsed,
        @JsonProperty("outputCharsUsed") int outputCharsUsed,
        @JsonProperty("checkpointId") String checkpointId,
        @JsonProperty("modelUnavailable") boolean modelUnavailable,
        @JsonProperty("error") String error,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("finishedAt") String finishedAt) {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CHECKPOINTED = "CHECKPOINTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** The design §7.1 terminal state for a user-stopped / cancelled run. The
     *  Demo uses {@code STOPPED}; it is the same terminal as
     *  {@link #STATUS_CANCELLED} under a design-aligned name. A terminal state
     *  can never re-enter RUNNING. */
    public static final String STATUS_STOPPED = "STOPPED";

    @JsonCreator
    public AgentRun {}
}
