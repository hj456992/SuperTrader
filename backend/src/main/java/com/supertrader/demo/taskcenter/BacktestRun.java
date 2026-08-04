package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The result of one local deterministic backtest execution (Module 9).
 *
 * <p>Created ONLY by the local Backtest Runner from a frozen whitelisted
 * SMA_CROSS / PRICE_BREAKOUT / EVENT_SIGNAL
 * snapshot + a registered local dataset. The metrics are computed
 * deterministically in Java — the run NEVER calls an LLM, AgentScope, SimNow
 * or the native probe. A retry always creates a NEW attempt (attemptNumber =
 * max+1); old results are never overwritten. Completed runs (SUCCEEDED /
 * FAILED / CANCELLED) are immutable: the load repair never drops or rewrites
 * them. {@code sampleOutStatus} is SAMPLE_ONLY for the built-in acceptance
 * datasets (非真实行情，不构成投资建议) or OOS_NOT_AVAILABLE / REAL for real
 * authorized data (V1 has none — real strategies use the acceptance sample or
 * DATA_UNAVAILABLE).
 */
public record BacktestRun(
        @JsonProperty("id") String id,
        @JsonProperty("taskId") String taskId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("attemptNumber") int attemptNumber,
        @JsonProperty("status") String status,
        @JsonProperty("datasetId") String datasetId,
        @JsonProperty("instrument") String instrument,
        @JsonProperty("timeframe") String timeframe,
        @JsonProperty("periodStart") String periodStart,
        @JsonProperty("periodEnd") String periodEnd,
        @JsonProperty("inputBars") int inputBars,
        @JsonProperty("trades") int trades,
        @JsonProperty("grossReturnPct") Double grossReturnPct,
        @JsonProperty("netReturnPct") Double netReturnPct,
        @JsonProperty("maxDrawdownPct") Double maxDrawdownPct,
        @JsonProperty("winRate") Double winRate,
        @JsonProperty("feeAssumptionBps") Integer feeAssumptionBps,
        @JsonProperty("slippageAssumptionBps") Integer slippageAssumptionBps,
        @JsonProperty("sampleOutStatus") String sampleOutStatus,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("errorCode") String errorCode,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("startedAt") String startedAt,
        @JsonProperty("finishedAt") String finishedAt,
        @JsonProperty("templateType") String templateType,
        @JsonProperty("snapshotContentHash") String snapshotContentHash,
        @JsonProperty("datasetContentHash") String datasetContentHash,
        @JsonProperty("executionTiming") String executionTiming,
        @JsonProperty("orders") List<BacktestRunnerService.OrderFill> orders,
        @JsonProperty("schemaVersion") Integer schemaVersion) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String SAMPLE_OUT_SAMPLE_ONLY = "SAMPLE_ONLY";

    /** Minimum input bars for a backtest to be meaningful (fail-closed below). */
    public static final int MIN_INPUT_BARS = 200;

    @JsonCreator
    public BacktestRun {}

    /** Source compatibility for the pre-versioned replay-metadata shape. */
    public BacktestRun(String id, String taskId, String workspaceId, int attemptNumber,
                       String status, String datasetId, String instrument, String timeframe,
                       String periodStart, String periodEnd, int inputBars, int trades,
                       Double grossReturnPct, Double netReturnPct, Double maxDrawdownPct,
                       Double winRate, Integer feeAssumptionBps,
                       Integer slippageAssumptionBps, String sampleOutStatus,
                       long durationMs, String errorCode, String errorMessage,
                       String startedAt, String finishedAt, String templateType,
                       String snapshotContentHash, String datasetContentHash,
                       String executionTiming, List<BacktestRunnerService.OrderFill> orders) {
        this(id, taskId, workspaceId, attemptNumber, status, datasetId, instrument,
                timeframe, periodStart, periodEnd, inputBars, trades, grossReturnPct,
                netReturnPct, maxDrawdownPct, winRate, feeAssumptionBps,
                slippageAssumptionBps, sampleOutStatus, durationMs, errorCode,
                errorMessage, startedAt, finishedAt, templateType, snapshotContentHash,
                datasetContentHash, executionTiming, orders, null);
    }

    /** Legacy task-center.v1 constructor/read shape remains source compatible. */
    public BacktestRun(String id, String taskId, String workspaceId, int attemptNumber,
                       String status, String datasetId, String instrument, String timeframe,
                       String periodStart, String periodEnd, int inputBars, int trades,
                       Double grossReturnPct, Double netReturnPct, Double maxDrawdownPct,
                       Double winRate, Integer feeAssumptionBps,
                       Integer slippageAssumptionBps, String sampleOutStatus,
                       long durationMs, String errorCode, String errorMessage,
                       String startedAt, String finishedAt) {
        this(id, taskId, workspaceId, attemptNumber, status, datasetId, instrument,
                timeframe, periodStart, periodEnd, inputBars, trades, grossReturnPct,
                netReturnPct, maxDrawdownPct, winRate, feeAssumptionBps,
                slippageAssumptionBps, sampleOutStatus, durationMs, errorCode,
                errorMessage, startedAt, finishedAt, null, null, null, null, List.of(), null);
    }
}
