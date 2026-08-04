package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A strategy candidate ("StrategySeed") detected inside a USER turn (Module 9).
 *
 * <p>Detection NEVER creates a Draft: a seed stays {@link #STATUS_DETECTED}
 * until the user explicitly chooses to enter strategy co-creation
 * ({@link #STATUS_CONFIRMED}, which creates a Draft) or to keep it as a
 * discussion only ({@link #STATUS_DISCUSSED}). Seeds are embedded in the
 * owning turn (there is deliberately no top-level seed array in the
 * task-center.v1 protocol).
 *
 * <p>Allowed fields (strict whitelist): id / summary / instruments /
 * entryHint / exitHint / riskHint / intent / status / sourceTurnId /
 * createdAt / decidedAt. No credential, account or trading-execution field
 * exists anywhere in this object.
 */
public record StrategySeed(
        @JsonProperty("id") String id,
        @JsonProperty("summary") String summary,
        @JsonProperty("instruments") List<String> instruments,
        @JsonProperty("entryHint") String entryHint,
        @JsonProperty("exitHint") String exitHint,
        @JsonProperty("riskHint") String riskHint,
        @JsonProperty("intent") String intent,
        @JsonProperty("status") String status,
        @JsonProperty("sourceTurnId") String sourceTurnId,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("decidedAt") String decidedAt) {

    public static final String STATUS_DETECTED = "DETECTED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_DISCUSSED = "DISCUSSED";

    @JsonCreator
    public StrategySeed {}
}
