package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * An IMMUTABLE frozen StrategySpec snapshot (Module 9).
 *
 * <p>Created ONLY by the approve-and-freeze controlled transaction: the
 * readable Draft summary is mapped into a new Module-8 immutable
 * StrategyVersion AND this strictly-whitelisted snapshot, linked by
 * strategyId + versionNumber, in one recoverable transaction. Once written a
 * snapshot can NEVER be modified in place, dropped or rewritten by the load
 * repair (immutability — the repair deliberately keeps even orphaned frozen
 * snapshots). Changing a frozen strategy requires a NEW Draft.
 *
 * <p>{@code contentHash} is the SHA-256 of the canonical snapshot content,
 * making the frozen content tamper-evident.
 */
public record StrategySpecSnapshot(
        @JsonProperty("id") String id,
        @JsonProperty("draftId") String draftId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("strategyId") String strategyId,
        @JsonProperty("versionNumber") int versionNumber,
        @JsonProperty("name") String name,
        @JsonProperty("templateType") String templateType,
        @JsonProperty("instruments") List<String> instruments,
        @JsonProperty("timeframe") String timeframe,
        @JsonProperty("parameters") SpecParameters parameters,
        @JsonProperty("entryCondition") String entryCondition,
        @JsonProperty("exitCondition") String exitCondition,
        @JsonProperty("riskLimits") String riskLimits,
        @JsonProperty("backtestAssumptions") String backtestAssumptions,
        @JsonProperty("contentHash") String contentHash,
        @JsonProperty("frozenAt") String frozenAt,
        @JsonProperty("frozenByMemberId") String frozenByMemberId) {

    @JsonCreator
    public StrategySpecSnapshot {}
}
