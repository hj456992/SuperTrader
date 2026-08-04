package com.supertrader.demo.taskcenter;

import java.util.function.BooleanSupplier;

/**
 * Executes ONE deterministic backtest attempt for the Strategy Execution
 * Harness (Module 9).
 *
 * <p>The production implementation is {@link BacktestRunnerService}. Tests
 * inject latch/barrier-controlled fakes through
 * {@link TaskCenterStore#setExecutorForTests(BacktestExecutor)} so the
 * RUNNING-cancellation and attempt-lifecycle races can be verified
 * deterministically (event-driven, no sleeps). The executor runs OUTSIDE the
 * store lock and MUST poll {@code cancelled} cooperatively; the returned
 * result is conditionally merged by the store (a cancelled attempt is never
 * overwritten by a completed result).
 */
interface BacktestExecutor {

    /**
     * Run one backtest attempt. {@code cancelled} turns true exactly when a
     * local task cancellation was requested for THIS attempt; the runner must
     * poll it and should stop as soon as possible (a result with
     * {@link BacktestRunnerService#ERROR_CANCELLED} is fine, but the store
     * decides the final state from the persisted record, never from the
     * result alone).
     */
    BacktestRunnerService.BacktestResult run(StrategySpecSnapshot snapshot,
                                             String datasetId,
                                             BooleanSupplier cancelled);
}
