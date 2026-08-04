package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A saved checkpoint of an {@link AgentRun} (Module 9). Saved when the run
 * budget is exhausted (BUDGET_EXHAUSTED / MAX_STEPS / MAX_TOOL_CALLS /
 * MAX_OUTPUT_CHARS) so the run can never loop forever and the state is
 * preserved for review.
 */
public record RunCheckpoint(
        @JsonProperty("id") String id,
        @JsonProperty("runId") String runId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("stepSeq") int stepSeq,
        @JsonProperty("reason") String reason,
        @JsonProperty("createdAt") String createdAt) {

    public static final String REASON_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    public static final String REASON_MAX_STEPS = "MAX_STEPS";
    public static final String REASON_MAX_TOOL_CALLS = "MAX_TOOL_CALLS";
    public static final String REASON_MAX_OUTPUT_CHARS = "MAX_OUTPUT_CHARS";

    @JsonCreator
    public RunCheckpoint {}
}
