package com.supertrader.demo.strategy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A strategy definition — research metadata ONLY (Module 8).
 *
 * <p>This object deliberately carries NO credential, token, private key,
 * account, order/cancel field or any trading capability. A strategy is a
 * "research definition" (rules text + instrument list + timeframe); the system
 * NEVER runs, backtests or signals from it, and it can never be bound to a
 * SimNow account.
 *
 * <p>Allowed fields (the strict whitelist enforced on load and on every
 * response):
 * <ul>
 *   <li>{@code id} — stable unique id (a UUID), server-generated; a client
 *       supplied id is NEVER adopted</li>
 *   <li>{@code workspaceId} — the owning workspace id (strict per-workspace
 *       isolation, enforced server-side; a strategy of another workspace is
 *       deliberately "not found")</li>
 *   <li>{@code name} — a human label (server-validated, trimmed, 1..64 chars,
 *       case-insensitively unique per workspace)</li>
 *   <li>{@code status} — {@link #STATUS_ACTIVE} or {@link #STATUS_ARCHIVED};
 *       an archived strategy can still be viewed but can NOT be renamed or
 *       versioned</li>
 *   <li>{@code currentVersion} — the current version number, ALWAYS computed
 *       from the valid version records; a client-supplied value is never
 *       adopted</li>
 *   <li>{@code createdByMemberId} — the local member who created the strategy
 *       (server-derived from the current actor)</li>
 *   <li>{@code createdAt} / {@code updatedAt} — ISO-8601 UTC timestamps</li>
 *   <li>{@code archivedAt} — ISO-8601 UTC archive time, or {@code null} while
 *       the strategy is active</li>
 * </ul>
 *
 * <p>Versions are APPEND-ONLY (see {@link StrategyVersion}): they can never be
 * updated, overwritten, deleted, rolled back or renumbered, and the version
 * numbers are strictly increasing starting at 1. Every strategy is created
 * together with version 1 in ONE atomic write.
 */
public record Strategy(
        @JsonProperty("id") String id,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("currentVersion") int currentVersion,
        @JsonProperty("createdByMemberId") String createdByMemberId,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("archivedAt") String archivedAt) {

    /** Active: may be renamed and versioned. */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** Archived: read-only (view + history only), name stays reserved. */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @JsonCreator
    public Strategy {}
}
