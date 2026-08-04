package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One traced step of an {@link AgentRun} (Module 9).
 *
 * <p>Kinds: THINK (intent classification / planning), TOOL (a gated ToolProxy
 * call — {@code capability} is set), QUESTION (the single field question) and
 * ANSWER (the reply text). {@code input} / {@code output} hold SHORT SANITISED
 * summaries (credential-shaped content is masked before storage; raw user
 * message content is never stored here), {@code truncated} marks an output
 * that hit the size limit, and {@code durationMs} records the step duration.
 */
public record AgentStep(
        @JsonProperty("id") String id,
        @JsonProperty("runId") String runId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("seq") int seq,
        @JsonProperty("kind") String kind,
        @JsonProperty("capability") String capability,
        @JsonProperty("input") String input,
        @JsonProperty("output") String output,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("createdAt") String createdAt) {

    public static final String KIND_THINK = "THINK";
    public static final String KIND_TOOL = "TOOL";
    public static final String KIND_QUESTION = "QUESTION";
    public static final String KIND_ANSWER = "ANSWER";

    @JsonCreator
    public AgentStep {}
}
