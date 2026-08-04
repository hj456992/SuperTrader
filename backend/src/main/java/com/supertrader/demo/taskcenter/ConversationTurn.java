package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single conversation turn (Module 9). A USER turn may carry a detected
 * {@link StrategySeed} (nullable); an ASSISTANT turn may carry at most ONE
 * {@link TurnQuestion} (the "one highest-impact field per round" rule).
 *
 * <p>Roles: {@link #ROLE_USER} / {@link #ROLE_ASSISTANT}. The content is
 * validated server-side (trimmed, 1..4000 chars, no control characters) and
 * credential-shaped content is rejected before it ever reaches the store.
 * Intents are the deterministic router labels: GENERAL_QA / RESEARCH /
 * STRATEGY_HYPOTHESIS / STRATEGY_CANDIDATE / STRATEGY_REFINEMENT /
 * TASK_REQUEST. {@code modelUnavailable} records whether the assistant reply
 * was generated without a model (MODEL_UNAVAILABLE) — it is never faked.
 *
 * <p>No credential, account, order/cancel or trading field exists anywhere in
 * this object.
 */
public record ConversationTurn(
        @JsonProperty("id") String id,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("intent") String intent,
        @JsonProperty("modelUnavailable") boolean modelUnavailable,
        @JsonProperty("seed") StrategySeed seed,
        @JsonProperty("question") TurnQuestion question,
        @JsonProperty("createdAt") String createdAt) {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    @JsonCreator
    public ConversationTurn {}
}
