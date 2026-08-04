package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The fixed per-run budget of an {@link AgentRun} (Module 9). Server-side
 * constants; never supplied by a client.
 */
public record RunBudget(
        @JsonProperty("maxSteps") int maxSteps,
        @JsonProperty("maxToolCalls") int maxToolCalls,
        @JsonProperty("maxOutputChars") int maxOutputChars) {

    @JsonCreator
    public RunBudget {}
}
