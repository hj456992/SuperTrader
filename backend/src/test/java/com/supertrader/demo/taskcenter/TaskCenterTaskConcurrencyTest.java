package com.supertrader.demo.taskcenter;

import com.supertrader.demo.team.TeamStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module 9 independent-acceptance regression (final round): REAL RUNNING
 * cancellation, the attempt lifecycle, cancellation-signal hygiene and the
 * executor exception path.
 *
 * <p>Guards:
 * <ul>
 *   <li>the backtest computation NEVER runs while the store lock is held — a
 *       second request (cancelTask) can cancel a genuinely RUNNING attempt;</li>
 *   <li>the runner polls a REAL per-attempt cancellation signal (never a fixed
 *       {@code false});</li>
 *   <li>the cancellation signal is registered by beginStart UNDER THE LOCK and
 *       removed by startTask's outer finally (same key AND object) — QUEUED
 *       cancels never create flags, so the flag table is EMPTY between runs
 *       (asserted via {@link TaskCenterStore#liveCancellationSignalsForTests()});</li>
 *   <li>a persisted CANCELLED state is NEVER overwritten by a completed
 *       result, and no completion audit is added;</li>
 *   <li>an executor that THROWS converges to a controlled FAILED (fixed,
 *       sanitised error) — the flag is cleared and retry still creates N+1;</li>
 *   <li>every concurrent startTask is executed on an ExecutorService and its
 *       Future result/exception is asserted on the MAIN test thread (no bare
 *       threads, no sleeps — event-driven latches + explicit timeouts).</li>
 * </ul>
 */
class TaskCenterTaskConcurrencyTest {

    private TaskCenterTestSupport.Stack stack;
    private String ownerId;

    @BeforeEach
    void setUp() throws Exception {
        stack = TaskCenterTestSupport.build();
        TaskCenterTestSupport.copyAcceptanceDatasets(stack);
        writeTinyDataset(stack); // a covering-but-undersized fixture → FAILED runs
        ownerId = stack.teams().currentActor().id();
    }

    // ------------------------------------------------------------------ //
    // REAL RUNNING cancellation (concurrent, latch-controlled)
    // ------------------------------------------------------------------ //

    @Test
    void runningTaskCanBeCancelledConcurrentlyFromAnotherRequest() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");

        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        AtomicBoolean cancelObserved = new AtomicBoolean(false);
        store.setExecutorForTests((snapshot, datasetId, cancelled) -> {
            runnerStarted.countDown();
            try {
                if (!releaseRunner.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test timeout: runner not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            cancelObserved.set(cancelled.getAsBoolean());
            return cancelled.getAsBoolean()
                    ? new BacktestRunnerService.BacktestResult(BacktestRun.STATUS_FAILED,
                            datasetId, "JM2609", "1D", null, null, 0, 0, null, null,
                            null, null, null, null, null, 0,
                            BacktestRunnerService.ERROR_CANCELLED,
                            "回测已取消（本地任务取消，与交易撤单无关）")
                    : stack.runner().run(snapshot, datasetId, cancelled);
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskCenterStore.TaskStartResult> startFuture =
                    pool.submit(() -> store.startTask(task.id()));
            // The runner is now executing OUTSIDE the store lock.
            assertTrue(runnerStarted.await(10, TimeUnit.SECONDS), "runner must start");
            var running = store.task(task.id());
            assertEquals(AgentTask.STATUS_RUNNING, running.task().status(),
                    "the attempt is really RUNNING while the runner executes");
            assertEquals(1, running.runs().get(0).attemptNumber());

            // A second request cancels the genuinely RUNNING attempt.
            var cancelled = store.cancelTask(task.id(), "并行取消");
            assertEquals(AgentTask.STATUS_CANCELLED, cancelled.status());
            assertEquals("并行取消", cancelled.cancelReason());
            var afterCancel = store.task(task.id());
            assertEquals(BacktestRun.STATUS_CANCELLED, afterCancel.runs().get(0).status(),
                    "the in-flight attempt is persisted CANCELLED");

            releaseRunner.countDown();
            // The START call must return normally on the main thread — the
            // Future result is CANCELLED and consistent with the disk state.
            TaskCenterStore.TaskStartResult startResult =
                    startFuture.get(10, TimeUnit.SECONDS);
            assertEquals(AgentTask.STATUS_CANCELLED, startResult.task().status(),
                    "startTask returns the CANCELLED task (never a completion result)");
            assertEquals(BacktestRun.STATUS_CANCELLED, startResult.run().status());
            assertTrue(cancelObserved.get(),
                    "the runner MUST observe the real cancellation signal (never a fixed false)");

            var finalTask = store.task(task.id());
            assertEquals(AgentTask.STATUS_CANCELLED, finalTask.task().status());
            assertEquals(BacktestRun.STATUS_CANCELLED, finalTask.runs().get(0).status());
            assertEquals(BacktestRunnerService.ERROR_CANCELLED, finalTask.runs().get(0).errorCode());
            var audit = store.taskAudit(task.id()).auditEvents().stream()
                    .map(AuditEvent::action).toList();
            assertTrue(audit.contains("TASK_STARTED"), "TASK_STARTED audit must exist");
            assertTrue(audit.contains("TASK_CANCELLED"), "TASK_CANCELLED audit must exist");
            assertFalse(audit.contains("TASK_RUN_SUCCEEDED"),
                    "no completion audit may appear for a cancelled attempt");
            assertFalse(audit.contains("TASK_RUN_FAILED"),
                    "no completion audit may appear for a cancelled attempt");
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "the cancellation signal must be gone after the run lifecycle");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "test executor must terminate");
        }
    }

    @Test
    void cancelBeforeFinishNeverOverwrittenByCompletedResult() throws Exception {
        // Race order: cancel persists CANCELLED, then the runner completes with
        // a SUCCEEDED result (it did not poll the flag in time). The store must
        // decide from the PERSISTED state, never from the result alone.
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");

        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        store.setExecutorForTests((snapshot, datasetId, cancelled) -> {
            runnerStarted.countDown();
            try {
                if (!releaseRunner.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test timeout: runner not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            // Deliberately ignore the just-set cancellation signal to exercise
            // cancel-wins, but return a fully consistent deterministic success.
            return stack.runner().run(snapshot, datasetId, () -> false);
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskCenterStore.TaskStartResult> startFuture =
                    pool.submit(() -> store.startTask(task.id()));
            assertTrue(runnerStarted.await(10, TimeUnit.SECONDS), "runner must start");
            store.cancelTask(task.id(), "竞态取消");
            releaseRunner.countDown();
            TaskCenterStore.TaskStartResult startResult =
                    startFuture.get(10, TimeUnit.SECONDS);
            assertEquals(AgentTask.STATUS_CANCELLED, startResult.task().status(),
                    "startTask returns normally with CANCELLED (race won by the cancel)");
            var finalTask = store.task(task.id());
            assertEquals(AgentTask.STATUS_CANCELLED, finalTask.task().status(),
                    "CANCELLED must win the race — never overwritten by SUCCEEDED");
            assertEquals(BacktestRun.STATUS_CANCELLED, finalTask.runs().get(0).status());
            var audit = store.taskAudit(task.id()).auditEvents().stream()
                    .map(AuditEvent::action).toList();
            assertFalse(audit.contains("TASK_RUN_SUCCEEDED"),
                    "no TASK_RUN_SUCCEEDED may appear after a cancellation");
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "no leaked cancellation signal after the race");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "test executor must terminate");
        }
    }

    @Test
    void secondStartWhileRunningIsRefused() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");

        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        store.setExecutorForTests((snapshot, datasetId, cancelled) -> {
            runnerStarted.countDown();
            try {
                if (!releaseRunner.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test timeout: runner not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return stack.runner().run(snapshot, datasetId, cancelled);
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskCenterStore.TaskStartResult> first =
                    pool.submit(() -> store.startTask(task.id()));
            assertTrue(runnerStarted.await(10, TimeUnit.SECONDS), "runner must start");
            // A concurrent double start is refused while the attempt runs.
            var e = assertThrows(TaskCenterApiException.class, () -> store.startTask(task.id()));
            assertEquals(409, e.status().value());
            assertEquals("ILLEGAL_STATE", e.code());
            releaseRunner.countDown();
            TaskCenterStore.TaskStartResult firstResult = first.get(10, TimeUnit.SECONDS);
            assertEquals(AgentTask.STATUS_SUCCEEDED, firstResult.task().status(),
                    "the FIRST start completes normally with SUCCEEDED");
            assertEquals(1, store.task(task.id()).runs().size(),
                    "only ONE attempt exists after a double-start attempt");
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "no leaked cancellation signal after the double start");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "test executor must terminate");
        }
    }

    // ------------------------------------------------------------------ //
    // Attempt lifecycle: retry → consume the QUEUED attempt on start
    // ------------------------------------------------------------------ //

    @Test
    void failedRetryThenStartConsumesTheQueuedAttempt() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, "tiny-1d-jm2609");
        store.approveTask(task.id(), "");
        var first = store.startTask(task.id());
        assertEquals(AgentTask.STATUS_FAILED, first.task().status());
        assertEquals("SAMPLE_TOO_SMALL", first.run().errorCode());
        assertEquals(1, first.run().attemptNumber());

        // Retry: exactly ONE new QUEUED attempt (N+1 = 2).
        var retried = store.retryTask(task.id());
        assertEquals(AgentTask.STATUS_QUEUED, retried.status());
        assertEquals(2, retried.attemptCount());
        var afterRetry = store.task(task.id());
        assertEquals(2, afterRetry.runs().size());
        assertEquals(BacktestRun.STATUS_QUEUED, afterRetry.runs().get(1).status());
        assertEquals(2, afterRetry.runs().get(1).attemptNumber());

        // Manual start CONSUMES the queued attempt — never creates attempt 3.
        var started = store.startTask(task.id());
        assertEquals(2, started.run().attemptNumber());
        var finalDetail = store.task(task.id());
        assertEquals(2, finalDetail.runs().size(),
                "no extra attempt may be created (no orphan QUEUED run)");
        assertEquals(BacktestRun.STATUS_FAILED, finalDetail.runs().get(0).status(),
                "old attempt-1 failure is never overwritten");
        assertEquals("SAMPLE_TOO_SMALL", finalDetail.runs().get(0).errorCode());
        assertEquals(BacktestRun.STATUS_FAILED, finalDetail.runs().get(1).status());
        assertEquals(2, finalDetail.task().attemptCount());
        assertTrue(finalDetail.runs().stream()
                        .noneMatch(r -> BacktestRun.STATUS_QUEUED.equals(r.status())),
                "no QUEUED run may remain after the manual start");
        assertEquals(0, store.liveCancellationSignalsForTests(),
                "no leaked cancellation signal after retry→start");
    }

    @Test
    void cancelledThenRetryKeepsOldRunAndWaitsForManualStart() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, "tiny-1d-jm2609");
        store.approveTask(task.id(), "");
        var first = store.startTask(task.id());
        assertEquals(AgentTask.STATUS_FAILED, first.task().status());

        store.retryTask(task.id());                       // attempt 2 QUEUED
        var cancelled = store.cancelTask(task.id(), "重试后取消");
        assertEquals(AgentTask.STATUS_CANCELLED, cancelled.status());
        var afterCancel = store.task(task.id());
        assertEquals(BacktestRun.STATUS_CANCELLED, afterCancel.runs().get(1).status(),
                "the QUEUED retry attempt is cancelled");
        assertEquals(BacktestRun.STATUS_FAILED, afterCancel.runs().get(0).status(),
                "old attempt-1 failure is untouched");

        // Retry again: exactly ONE more contiguous attempt (3), old run intact,
        // and NOTHING runs automatically.
        var retried = store.retryTask(task.id());
        assertEquals(AgentTask.STATUS_QUEUED, retried.status());
        assertEquals(3, retried.attemptCount());
        var detail = store.task(task.id());
        assertEquals(3, detail.runs().size());
        assertEquals(List.of(BacktestRun.STATUS_FAILED, BacktestRun.STATUS_CANCELLED,
                BacktestRun.STATUS_QUEUED), detail.runs().stream()
                .map(BacktestRun::status).toList());
        assertEquals(List.of(1, 2, 3), detail.runs().stream()
                .map(BacktestRun::attemptNumber).toList(),
                "attempt numbers stay contiguous");
        assertTrue(detail.runs().stream().noneMatch(r ->
                        BacktestRun.STATUS_RUNNING.equals(r.status())),
                "a retry must NEVER auto-start");

        // The new attempt still needs the manual start click; it consumes
        // attempt 3 and creates nothing further.
        var started = store.startTask(task.id());
        assertEquals(3, started.run().attemptNumber());
        var finalDetail = store.task(task.id());
        assertEquals(3, finalDetail.runs().size(), "no attempt 4 / no orphan run");
        assertEquals(BacktestRun.STATUS_FAILED, finalDetail.runs().get(2).status());
        assertEquals(0, store.liveCancellationSignalsForTests(),
                "no leaked cancellation signal after the full retry/cancel cycle");
    }

    // ------------------------------------------------------------------ //
    // Cancellation-signal hygiene: QUEUED cancels never create flags
    // ------------------------------------------------------------------ //

    @Test
    void queuedCancelNeverLeaksCancellationSignals() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, "tiny-1d-jm2609");
        store.approveTask(task.id(), "");
        assertEquals(AgentTask.STATUS_FAILED, store.startTask(task.id()).task().status());

        // Repeated FAILED → retry → QUEUED → cancel cycles: each retry creates
        // exactly one QUEUED attempt; cancelling it must NEVER create a
        // cancellation signal (there is no runner to poll it).
        for (int i = 0; i < 3; i++) {
            var retried = store.retryTask(task.id());
            assertEquals(AgentTask.STATUS_QUEUED, retried.status());
            var cancelled = store.cancelTask(task.id(), "多轮取消");
            assertEquals(AgentTask.STATUS_CANCELLED, cancelled.status());
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "QUEUED cancels must not leak cancellation signals (round " + (i + 1) + ")");
        }
        var detail = store.task(task.id());
        assertEquals(4, detail.runs().size(),
                "each retry created exactly one attempt (1 FAILED + 3 CANCELLED)");
        assertEquals(0, store.liveCancellationSignalsForTests(),
                "the signal table stays empty after all QUEUED cancels");
    }

    // ------------------------------------------------------------------ //
    // Executor exception path
    // ------------------------------------------------------------------ //

    @Test
    void executorExceptionConvergesToControlledFailed() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        store.setExecutorForTests((snapshot, datasetId, cancelled) -> {
            throw new IllegalStateException("boom: /secret/path 密码=xxx"); // must stay sanitised
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskCenterStore.TaskStartResult> future =
                    pool.submit(() -> store.startTask(task.id()));
            // The start call RETURNS normally (no exception escapes) with a
            // controlled FAILED result.
            TaskCenterStore.TaskStartResult result = future.get(10, TimeUnit.SECONDS);
            assertEquals(AgentTask.STATUS_FAILED, result.task().status(),
                    "executor exception converges to a controlled FAILED");
            assertEquals(BacktestRun.STATUS_FAILED, result.run().status());
            assertEquals(BacktestRunnerService.ERROR_INTERNAL, result.run().errorCode(),
                    "fixed sanitised error code");
            String message = result.run().errorMessage();
            assertNotNull(message);
            assertFalse(message.contains("boom"), "exception text must never leak");
            assertFalse(message.contains("/secret/path"), "paths must never leak");
            assertFalse(message.contains("密码"), "credential-shaped content must never leak");

            var disk = store.task(task.id());
            assertEquals(BacktestRun.STATUS_FAILED, disk.runs().get(0).status());
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "the cancellation signal is cleared even on executor failure");
            var audit = store.taskAudit(task.id()).auditEvents().stream()
                    .map(AuditEvent::action).toList();
            assertTrue(audit.contains("TASK_RUN_FAILED"), "TASK_RUN_FAILED audit must exist");
            assertFalse(audit.stream().anyMatch(a ->
                    a.contains("boom") || a.contains("/secret/path") || a.contains("密码")),
                    "audit must be sanitised too");

            // The task is FAILED (not RUNNING): retry still works and creates
            // exactly one N+1 QUEUED attempt.
            var retried = store.retryTask(task.id());
            assertEquals(AgentTask.STATUS_QUEUED, retried.status());
            assertEquals(2, retried.attemptCount());
            assertEquals(2, store.task(task.id()).runs().size());
            assertEquals(0, store.liveCancellationSignalsForTests());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "test executor must terminate");
        }
    }

    // ------------------------------------------------------------------ //
    // Persist-failure edge path: beginStart() registers the cancellation
    // signal BEFORE persisting RUNNING + TASK_STARTED. When that persist
    // FAILS the signal must be removed again (same key + same object) before
    // the exception propagates — no leaked flag, no RUNNING half-state, no
    // spurious audit, and a manual start works again after the fault heals.
    // ------------------------------------------------------------------ //

    @Test
    void persistFailureOnFirstStartLeavesNoSignalAndNoHalfState() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        assertEquals(0, store.liveCancellationSignalsForTests());

        store.setPersistHookForTests(() -> {
            throw new TaskCenterApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    TaskCenterApiException.CODE_PERSIST_FAILED, "模拟持久化故障");
        });
        try {
            var e = assertThrows(TaskCenterApiException.class,
                    () -> store.startTask(task.id()));
            assertEquals(500, e.status().value());
            assertEquals(TaskCenterApiException.CODE_PERSIST_FAILED, e.code());
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "the cancelled signal must be removed on a failed persist");
            var after = store.task(task.id());
            assertEquals(AgentTask.STATUS_APPROVED, after.task().status(),
                    "the task must NOT be left RUNNING after a failed persist");
            assertEquals(0, after.runs().size(),
                    "no attempt may be persisted on a failed start persist");
            var audit = store.taskAudit(task.id()).auditEvents().stream()
                    .map(AuditEvent::action).toList();
            assertFalse(audit.contains("TASK_STARTED"),
                    "a failed persist must not leave a TASK_STARTED audit");
            assertFalse(audit.contains("TASK_RUN_SUCCEEDED"),
                    "a failed persist must not produce a spurious completion audit");
            assertFalse(audit.contains("TASK_RUN_FAILED"),
                    "a failed persist must not produce a spurious completion audit");
        } finally {
            store.setPersistHookForTests(null);
        }

        // After the persistence fault heals, the manual start works normally.
        var started = store.startTask(task.id());
        assertEquals(AgentTask.STATUS_SUCCEEDED, started.task().status());
        assertEquals(1, started.run().attemptNumber());
        assertEquals(0, store.liveCancellationSignalsForTests(),
                "the signal table is empty after a successful run");
    }

    @Test
    void persistFailureOnQueuedRetryStartLeavesNoSignalAndConsumesNothing() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, "tiny-1d-jm2609");
        store.approveTask(task.id(), "");
        assertEquals(AgentTask.STATUS_FAILED, store.startTask(task.id()).task().status());
        store.retryTask(task.id());                       // attempt 2 QUEUED
        var before = store.task(task.id());
        assertEquals(AgentTask.STATUS_QUEUED, before.task().status());
        assertEquals(BacktestRun.STATUS_QUEUED, before.runs().get(1).status());
        int startedAuditsBefore = store.taskAudit(task.id()).auditEvents().stream()
                .map(AuditEvent::action).filter("TASK_STARTED"::equals).toList().size();

        store.setPersistHookForTests(() -> {
            throw new TaskCenterApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    TaskCenterApiException.CODE_PERSIST_FAILED, "模拟持久化故障");
        });
        try {
            var e = assertThrows(TaskCenterApiException.class,
                    () -> store.startTask(task.id()));
            assertEquals(500, e.status().value());
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "no leaked signal after a failed QUEUED-start persist");
            var after = store.task(task.id());
            assertEquals(AgentTask.STATUS_QUEUED, after.task().status(),
                    "the retry attempt stays QUEUED — nothing was consumed");
            assertEquals(BacktestRun.STATUS_QUEUED, after.runs().get(1).status(),
                    "the QUEUED attempt stays QUEUED on disk");
            int startedAuditsAfter = store.taskAudit(task.id()).auditEvents().stream()
                    .map(AuditEvent::action).filter("TASK_STARTED"::equals).toList().size();
            assertEquals(startedAuditsBefore, startedAuditsAfter,
                    "no extra TASK_STARTED audit may appear after a failed persist");
        } finally {
            store.setPersistHookForTests(null);
        }

        // After the fault heals, the manual start CONSUMES the SAME attempt 2
        // (never creates attempt 3) and the old attempt-1 result is intact.
        var started = store.startTask(task.id());
        assertEquals(2, started.run().attemptNumber(),
                "the manual start consumes the existing QUEUED attempt");
        var finalDetail = store.task(task.id());
        assertEquals(2, finalDetail.runs().size(), "no extra attempt may appear");
        assertEquals(BacktestRun.STATUS_FAILED, finalDetail.runs().get(0).status(),
                "old attempt-1 failure is never overwritten");
        assertEquals("SAMPLE_TOO_SMALL", finalDetail.runs().get(0).errorCode());
        assertEquals(BacktestRun.STATUS_FAILED, finalDetail.runs().get(1).status());
        assertEquals(0, store.liveCancellationSignalsForTests());
    }

    @Test
    void persistFailureOnOneTaskNeverRemovesAnotherAttemptsSignal() throws Exception {
        var store = stack.store();
        String draftA = freezeFreshDraft(store);
        var taskA = store.createBacktestTask(draftA, null);
        store.approveTask(taskA.id(), "");
        String draftB = freezeFreshDraft(store, "并发均线策略B");
        var taskB = store.createBacktestTask(draftB, null);
        store.approveTask(taskB.id(), "");

        // Task A runs OUTSIDE the store lock, blocked on a latch.
        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        store.setExecutorForTests((snapshot, datasetId, cancelled) -> {
            runnerStarted.countDown();
            try {
                if (!releaseRunner.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test timeout: runner not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return stack.runner().run(snapshot, datasetId, cancelled);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskCenterStore.TaskStartResult> futureA =
                    pool.submit(() -> store.startTask(taskA.id()));
            assertTrue(runnerStarted.await(10, TimeUnit.SECONDS), "runner A must start");
            assertEquals(1, store.liveCancellationSignalsForTests(),
                    "task A's live signal exists while its runner executes");

            // Task B's start fails its persist; only B's own signal may be
            // removed — task A's signal must survive.
            store.setPersistHookForTests(() -> {
                throw new TaskCenterApiException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        TaskCenterApiException.CODE_PERSIST_FAILED, "模拟持久化故障");
            });
            try {
                assertThrows(TaskCenterApiException.class,
                        () -> store.startTask(taskB.id()));
            } finally {
                store.setPersistHookForTests(null);
            }
            assertEquals(1, store.liveCancellationSignalsForTests(),
                    "task A's signal must NOT be removed by task B's failed persist");
            var taskBAfter = store.task(taskB.id());
            assertEquals(AgentTask.STATUS_APPROVED, taskBAfter.task().status(),
                    "task B stays APPROVED — no half state");

            releaseRunner.countDown();
            TaskCenterStore.TaskStartResult resultA = futureA.get(10, TimeUnit.SECONDS);
            assertEquals(AgentTask.STATUS_SUCCEEDED, resultA.task().status(),
                    "task A completes normally and is unaffected by task B's failure");
            assertEquals(0, store.liveCancellationSignalsForTests(),
                    "both signals are gone after the full lifecycle");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "test executor must terminate");
        }
    }

    @Test
    void inconsistentExecutorSuccessFailsClosedBeforeCommitAndSurvivesReload() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");

        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunner = new CountDownLatch(1);
        store.setExecutorForTests((snapshot, datasetId, cancelled) -> {
            runnerStarted.countDown();
            try {
                if (!releaseRunner.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test timeout: runner not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return new BacktestRunnerService.BacktestResult(
                    BacktestRun.STATUS_SUCCEEDED, datasetId, "JM2609", "1D",
                    "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z",
                    300, 5, 3.21, 2.9, 0.5, 40.0, 2, 1,
                    BacktestRun.SAMPLE_OUT_SAMPLE_ONLY, 0L, null, null);
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskCenterStore.TaskStartResult> future =
                    pool.submit(() -> store.startTask(task.id()));
            assertTrue(runnerStarted.await(10, TimeUnit.SECONDS), "runner must start");
            releaseRunner.countDown();

            TaskCenterStore.TaskStartResult result = future.get(10, TimeUnit.SECONDS);
            assertEquals(AgentTask.STATUS_FAILED, result.task().status(),
                    "trades without deterministic orders must fail before commit");
            assertEquals(BacktestRun.STATUS_FAILED, result.run().status());
            assertEquals("INVALID_RESULT", result.run().errorCode());

            var fresh = new TaskCenterStore(store.filePath().toString(),
                    stack.workspaces(), stack.teams(), stack.strategies(),
                    stack.validator(), stack.catalog(), stack.runner());
            var reloaded = fresh.task(task.id());
            assertEquals(AgentTask.STATUS_FAILED, reloaded.task().status());
            assertEquals(1, reloaded.runs().size(),
                    "the controlled FAILED run must survive strict repair");
            assertEquals("INVALID_RESULT", reloaded.runs().get(0).errorCode());
            var audit = fresh.taskAudit(task.id()).auditEvents();
            assertTrue(audit.stream().anyMatch(a -> "TASK_RUN_FAILED".equals(a.action())
                    && a.detail().contains("code=INVALID_RESULT")));
            assertFalse(audit.stream().anyMatch(a -> "TASK_RUN_SUCCEEDED".equals(a.action())));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                    "test executor must terminate");
        }
    }

    // ------------------------------------------------------------------ //
    // Permissions + workspace isolation for start/cancel
    // ------------------------------------------------------------------ //

    @Test
    void viewerCannotStartOrCancelAndCrossWorkspaceCancelIs404() {
        var store = stack.store();
        var teams = stack.teams();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");

        var viewer = teams.createMember("并发观察员", TeamStore.ROLE_VIEWER);
        teams.switchActor(viewer.id());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.startTask(task.id())).status().value());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.cancelTask(task.id(), "")).status().value());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.retryTask(task.id())).status().value());
        teams.switchActor(ownerId);

        // Cross-workspace cancel is a 404 (strict per-workspace isolation).
        var otherWs = stack.workspaces().create("隔离空间");
        stack.workspaces().switchTo(otherWs.id());
        assertEquals(404, assertThrows(TaskCenterApiException.class,
                () -> store.cancelTask(task.id(), "")).status().value());
        assertEquals(404, assertThrows(TaskCenterApiException.class,
                () -> store.startTask(task.id())).status().value());
    }

    // ------------------------------------------------------------------ //
    // Fixtures (mirroring TaskCenterStoreTest helpers)
    // ------------------------------------------------------------------ //

    private String freezeFreshDraft(TaskCenterStore store) {
        return freezeFreshDraft(store, "并发均线策略");
    }

    private String freezeFreshDraft(TaskCenterStore store, String strategyName) {
        var session = store.createSession("并发验收会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE,
                new StrategySeed("c1", "如果 JM2609 上穿20日均线就买入",
                        List.of("JM2609"), "上穿买入", "下穿卖出", null,
                        IntentClassifier.STRATEGY_CANDIDATE, StrategySeed.STATUS_DETECTED,
                        null, "2026-08-01T00:00:00Z", null));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        store.updateDraft(draft.id(), fullPatch(strategyName), null);
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");
        store.approveAndFreeze(draft.id(), "", ownerId);
        return draft.id();
    }

    private static TaskCenterDtos.DraftPatch fullPatch() {
        return fullPatch("并发均线策略");
    }

    private static TaskCenterDtos.DraftPatch fullPatch(String name) {
        return new TaskCenterDtos.DraftPatch(
                name, List.of("JM2609"), "1D", 5, 20, 0.1, 5.0, 2, 1,
                "快线上穿慢线时买入", "快线下穿慢线时卖出", "止损5%，仓位10%",
                "使用内置验收样本，手续费2bp，滑点1bp");
    }

    private static void writeTinyDataset(TaskCenterTestSupport.Stack stack) throws Exception {
        StringBuilder bars = new StringBuilder();
        double price = 1000.0;
        for (int i = 0; i < 50; i++) {
            price = price * (1 + 0.001 * (i % 3 - 1));
            String ts = java.time.Instant.parse("2026-01-01T00:00:00Z")
                    .plus(java.time.Duration.ofHours(i)).toString();
            bars.append("{\"ts\":\"").append(ts).append("\",\"open\":")
                 .append(price).append(",\"high\":").append(price * 1.001)
                 .append(",\"low\":").append(price * 0.999)
                 .append(",\"close\":").append(price).append(",\"volume\":100},");
        }
        String json = "{\"schema\":\"backtest-dataset.v1\","
                + "\"datasetId\":\"tiny-1d-jm2609\",\"instrument\":\"JM2609\","
                + "\"timeframe\":\"1D\",\"periodStart\":\"2026-01-01T00:00:00.000Z\","
                + "\"periodEnd\":\"2026-01-03T01:00:00.000Z\","
                + "\"generatedAt\":\"2026-08-02T00:00:00Z\",\"staleAfter\":null,"
                + "\"sampleOutStatus\":\"SAMPLE_ONLY\","
                + "\"description\":\"测试用样本\",\"bars\":["
                + bars.substring(0, bars.length() - 1) + "]}";
        Files.writeString(stack.datasetsDir().resolve("tiny-1d-jm2609.json"), json);
    }
}
