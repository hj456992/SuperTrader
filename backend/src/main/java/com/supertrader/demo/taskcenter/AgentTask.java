package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A task-center task (Module 9). V1 only creates BACKTEST tasks from an
 * approved + frozen + validated StrategySpecSnapshot; the Strategy Execution
 * Harness registers ONLY the local Backtest Runner (SIMNOW_WRITE / PAPER /
 * LIVE / FUTURE_GATEWAY are never registered).
 *
 * <p>Status machine (server-enforced):
 * <pre>
 * First run:
 *   PENDING_APPROVAL → APPROVED / REJECTED        (OWNER/ADMIN approval)
 *   APPROVED        → 人工 start → RUNNING → SUCCEEDED / FAILED / CANCELLED
 * Retry run:
 *   FAILED / CANCELLED → 人工 retry → QUEUED      (creates exactly one N+1
 *                                                   attempt; NEVER auto-runs)
 *   QUEUED          → 人工 start → RUNNING → SUCCEEDED / FAILED / CANCELLED
 *                                                   (consumes the queued run)
 * </pre>
 * start accepts APPROVED (first manual start) or QUEUED (retry needs another
 * manual start); QUEUED exists ONLY after a retry. cancel is allowed only on
 * QUEUED / RUNNING and is a LOCAL task cancellation — it has NO relation to
 * any trading order cancellation. Old attempt results are never overwritten.
 * There is NO auto-start, auto-retry, auto-approval, auto-promotion or
 * auto-trading.
 */
public record AgentTask(
        @JsonProperty("id") String id,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("draftId") String draftId,
        @JsonProperty("snapshotId") String snapshotId,
        @JsonProperty("strategyId") String strategyId,
        @JsonProperty("versionNumber") int versionNumber,
        @JsonProperty("type") String type,
        @JsonProperty("datasetId") String datasetId,
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("requestedByMemberId") String requestedByMemberId,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("cancelledAt") String cancelledAt,
        @JsonProperty("cancelReason") String cancelReason,
        @JsonProperty("attemptCount") int attemptCount) {

    public static final String TYPE_BACKTEST = "BACKTEST";

    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @JsonCreator
    public AgentTask {}
}
