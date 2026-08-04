package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The single highest-impact question the Agent asks in one round (Module 9).
 *
 * <p>The Agent asks AT MOST ONE field question per turn. {@code field} is one
 * of the StrategySpecDraft field names (instruments / timeframe / fastWindow /
 * slowWindow / positionSize / stopLossPct / feeBps / slippageBps /
 * entryCondition / exitCondition / riskLimits / backtestAssumptions / name).
 * The prompt is deterministic Chinese text; already-confirmed fields are never
 * asked again.
 */
public record TurnQuestion(
        @JsonProperty("field") String field,
        @JsonProperty("prompt") String prompt) {

    @JsonCreator
    public TurnQuestion {}
}
