package com.supertrader.demo.strategy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * An IMMUTABLE version of a strategy definition (Module 8).
 *
 * <p>Versions are strictly append-only: once written they can NEVER be
 * updated, overwritten, deleted, rolled back or renumbered. The version
 * numbers are strictly increasing and always start at 1 (created atomically
 * together with the strategy). A version is a research definition only — the
 * system NEVER runs, backtests or generates signals from it.
 *
 * <p>Allowed fields (the strict whitelist enforced on load and on every
 * response):
 * <ul>
 *   <li>{@code id} — stable unique id (a UUID), server-generated</li>
 *   <li>{@code strategyId} — the owning strategy id</li>
 *   <li>{@code versionNumber} — strictly increasing, starts at 1; a client
 *       supplied number is NEVER adopted (the server computes max+1)</li>
 *   <li>{@code summary} — 1..500 chars (trimmed, no control characters)</li>
 *   <li>{@code instruments} — 1..20 distinct public contract ids, each 1..16
 *       chars of letters/digits, stored in the canonical deterministic form
 *       (uppercased, de-duplicated, first-seen order)</li>
 *   <li>{@code timeframe} — one of the server-side whitelist values
 *       TICK / 1M / 5M / 15M / 30M / 1H / 1D (uppercased)</li>
 *   <li>{@code entryRules} / {@code exitRules} / {@code riskNotes} — free text,
 *       each at most 4000 chars with no control characters</li>
 *   <li>{@code createdByMemberId} — the local member who wrote the version
 *       (server-derived from the current actor)</li>
 *   <li>{@code createdAt} — ISO-8601 UTC timestamp</li>
 * </ul>
 *
 * <p>No credential, account, order/cancel field or any trading-capable field
 * exists anywhere in this object.
 */
public record StrategyVersion(
        @JsonProperty("id") String id,
        @JsonProperty("strategyId") String strategyId,
        @JsonProperty("versionNumber") int versionNumber,
        @JsonProperty("summary") String summary,
        @JsonProperty("instruments") List<String> instruments,
        @JsonProperty("timeframe") String timeframe,
        @JsonProperty("entryRules") String entryRules,
        @JsonProperty("exitRules") String exitRules,
        @JsonProperty("riskNotes") String riskNotes,
        @JsonProperty("createdByMemberId") String createdByMemberId,
        @JsonProperty("createdAt") String createdAt) {

    @JsonCreator
    public StrategyVersion {}
}
