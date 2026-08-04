package com.supertrader.demo.agentdemo;

import com.supertrader.demo.taskcenter.AgentRun;
import com.supertrader.demo.taskcenter.AgentRuntimeHarness;
import com.supertrader.demo.taskcenter.ConversationSession;
import com.supertrader.demo.taskcenter.HarnessObserver;
import com.supertrader.demo.taskcenter.HarnessTurnRequest;
import com.supertrader.demo.taskcenter.IntentInferencePort;
import com.supertrader.demo.taskcenter.RunCheckpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Agent Demo Run Coordinator (Task 7).
 *
 * <p>The ONLY place that bridges the durable {@link AgentDemoStore}, the SSE
 * {@link AgentDemoEventHub} and the unique {@link AgentRuntimeHarness}. It:
 * <ul>
 *   <li>accepts an already-persisted (T1) QUEUED Run and submits it to a
 *       bounded executor — at most one active Run per session and a server
 *       cap on concurrency + queue depth;</li>
 *   <li>builds a {@link HarnessTurnRequest} (server-owned runId / budget /
 *       cancel token) and runs the unique harness — there is NO second Agent
 *       kernel;</li>
 *   <li>writes every T2 event atomically to the Store BEFORE publishing to
 *       the Event Hub (Store is authoritative);</li>
 *   <li>persists T3 (AssistantTurn + IntentResult + Seed/Draft diff + terminal
 *       Run + audit-style events); a failure is honestly surfaced as FAILED;</li>
 *   <li>implements cooperative Stop (interrupt + per-run cancel token) — a
 *       stopped Run saves a Checkpoint, enters STOPPED and produces NO new
 *       Step / assistant / seed / draft success event;</li>
 *   <li>implements Retry — a new runId reusing the user content but NOT the
 *       old model result / budget usage; idempotency keys still apply.</li>
 * </ul>
 *
 * <p>Queue-full and session-busy paths return stable error codes
 * ({@code RUN_QUEUE_FULL} / {@code SESSION_BUSY}). Threads are named daemons
 * so the JVM exits cleanly; {@link #shutdown()} stops accepting work.
 */
public final class AgentDemoRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoRunCoordinator.class);

    private final AgentRuntimeHarness harness;
    private final AgentDemoStore store;
    private final AgentDemoEventHub hub;
    private final IntentInferencePort intentPort;
    private final ThreadPoolExecutor executor;
    private final int maxQueue;

    /** Per-run cancellation tokens (runId → cancel flag). */
    private final Map<String, AtomicBoolean> cancelTokens = new ConcurrentHashMap<>();
    /** Sessions that currently have an active (non-terminal) Run. */
    private final ConcurrentHashMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();
    /** Idempotency for Stop (key → outcome). */
    private final Map<String, StopOutcome> stopOutcomes = new ConcurrentHashMap<>();
    /** Idempotency for Retry (key → newRunId). */
    private final Map<String, String> retryKeys = new ConcurrentHashMap<>();
    /** Submitted futures so Stop can interrupt the worker. */
    private final Map<String, java.util.concurrent.Future<?>> futures = new ConcurrentHashMap<>();

    public AgentDemoRunCoordinator(AgentRuntimeHarness harness, AgentDemoStore store,
                                   AgentDemoEventHub hub, IntentInferencePort intentPort,
                                   int maxConcurrent, int maxQueue) {
        this.harness = harness;
        this.store = store;
        this.hub = hub;
        this.intentPort = intentPort == null ? IntentInferencePort.unavailable() : intentPort;
        this.maxQueue = maxQueue;
        ThreadFactory factory = new NamedDaemonFactory("agent-demo-run");
        // Bounded queue: when full, submit is rejected → caller gets RUN_QUEUE_FULL.
        this.executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(maxQueue), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    // ------------------------------------------------------------------ //
    // Submit
    // ------------------------------------------------------------------ //

    /**
     * Submit an already-persisted QUEUED Run to the executor. Returns a
     * rejected outcome ({@code RUN_QUEUE_FULL} / {@code SESSION_BUSY}) when the
     * executor cannot accept the work; otherwise the Run executes asynchronously.
     */
    public synchronized SubmitOutcome submit(AgentDemoStore.AcceptOutcome accepted,
                                             String sessionId, String content,
                                             com.supertrader.demo.taskcenter.StrategySpecDraft activeDraft,
                                             com.supertrader.demo.taskcenter.TurnQuestion pendingQuestion,
                                             String actorRole) {
        String runId = accepted.runId();
        // One active Run per session.
        if (activeSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            return SubmitOutcome.rejected("SESSION_BUSY", runId);
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancelTokens.put(runId, cancel);
        try {
            java.util.concurrent.Future<?> f = executor.submit(() ->
                    runTurn(runId, sessionId, content, activeDraft, pendingQuestion,
                            actorRole, cancel, accepted));
            futures.put(runId, f);
            return SubmitOutcome.accepted(runId);
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            activeSessions.remove(sessionId);
            cancelTokens.remove(runId);
            return SubmitOutcome.rejected("RUN_QUEUE_FULL", runId);
        }
    }

    // ------------------------------------------------------------------ //
    // The single execution path (no second Agent kernel)
    // ------------------------------------------------------------------ //

    private void runTurn(String runId, String sessionId, String content,
                         com.supertrader.demo.taskcenter.StrategySpecDraft activeDraft,
                         com.supertrader.demo.taskcenter.TurnQuestion pendingQuestion,
                         String actorRole, AtomicBoolean cancel,
                         AgentDemoStore.AcceptOutcome accepted) {
        try {
            ConversationSession session = store.snapshot().sessions().get(sessionId);
            if (session == null) {
                session = new ConversationSession(sessionId, "ws-agent-demo", "Demo",
                        ConversationSession.STATUS_ACTIVE, actorRole,
                        Instant.now().toString(), Instant.now().toString());
            }
            HarnessTurnRequest req = HarnessTurnRequest.builder()
                    .runId(runId)
                    .session(session)
                    .content(content)
                    .activeDraft(activeDraft)
                    .pendingQuestion(pendingQuestion)
                    .actorRole(actorRole)
                    .intentPort(intentPort)
                    .cancelled(cancel::get)
                    .build();

            // The observer writes every event atomically to the Store BEFORE
            // publishing to the Event Hub (T2).
            HarnessObserver observer = new HarnessObserver() {
                @Override
                public void onEvent(HarnessObserver.Event e) {
                    handleHarnessEvent(e, runId, sessionId, cancel);
                }
            };

            // P1-3（中文要点）：【唯一 Harness 负责】生命周期事件（run.started /
            // run.completed / run.failed / run.stopped）。Coordinator 【不再自发 run.started】
            // ——否则每个终态事件会发两次（旧 bug：Harness 和 Coordinator 各发一次）。
            // Harness 在第一个 observer 事件里就发 run.started。

            // 若取消发生在 harness 启动之前，直接进 STOPPED（不产生任何 step / assistant / seed / draft）。
            if (cancel.get()) {
                stopRun(runId, sessionId, null);
                return;
            }

            AgentRuntimeHarness.TurnExecution te;
            try {
                te = harness.executeTurn(req, observer);
            } catch (Throwable t) {
                failRun(runId, sessionId, t);
                return;
            }

            // If the cancel fired during execution, discard the result and go
            // STOPPED (no new assistant / seed / draft success event).
            if (cancel.get()) {
                stopRun(runId, sessionId, te);
                return;
            }

            // P1-3: durably persist the traced AgentSteps so a refresh / Session
            // Detail can restore stepsByRun. Done BEFORE completeRun so the
            // terminal write is the final atomic flush.
            if (te.steps() != null && !te.steps().isEmpty()) {
                try {
                    store.persistSteps(runId, sessionId, te.steps());
                } catch (Throwable t) {
                    log.debug("persistSteps failed for {}: {}", runId, t.toString());
                }
            }

            // T3: persist the assistant turn, intent, seed, checkpoint, terminal.
            String terminalStatus = te.run().status();
            String error = te.run().error();
            RunCheckpoint cp = te.checkpoint();
            if (!isTerminal(terminalStatus)) {
                terminalStatus = AgentRun.STATUS_COMPLETED;
            }
            store.completeRun(runId, sessionId, terminalStatus,
                    te.assistantContent(), te.intentResult(), te.seed(), cp, te.patch(),
                    te.run().stepsUsed(), te.run().toolCallsUsed(), 0,
                    te.run().outputCharsUsed(), error, te.modelUnavailable(),
                    te.question() == null ? null : te.question().prompt());
        } catch (Throwable t) {
            log.warn("run {} failed: {}", runId, t.toString(), t);
            try {
                failRun(runId, sessionId, t);
            } catch (Throwable ignore) {
                // last-resort
            }
        } finally {
            activeSessions.remove(sessionId);
            futures.remove(runId);
            // Remove the cancel token once the worker has finished so a later
            // Stop on this (now terminal) Run correctly reports already-terminal
            // instead of looking still-active. The durable Store status is the
            // single source of truth for terminality.
            cancelTokens.remove(runId);
        }
    }

    private void handleHarnessEvent(HarnessObserver.Event e, String runId, String sessionId,
                                    AtomicBoolean cancel) {
        if (cancel.get()) return; // 停止后丢弃所有事件（不写 Store）
        String type = e.type();
        // P1-3（中文要点）：【终态事件由 Coordinator 的 completeRun 单一负责】
        // （run.completed / run.failed / run.stopped / run.checkpointed）。Harness 也会经
        // observer 发这些，为避免【每个终态事件发两次】，这里把 Harness 的终态事件丢弃，
        // 让 completeRun 只发一次。非终态事件（run.started / step.* / intent.detected /
        // budget.updated / seed.detected / draft.updated / assistant.delta）正常透传持久化。
        if (isCoordinatorOwnedTerminal(type)) {
            return;
        }
        Map<String, Object> payload = e.payload() == null ? Map.of() : e.payload();
        try {
            store.appendEvent(sessionId, runId, type, payload);
        } catch (Throwable t) {
            log.debug("store appendEvent failed for {}: {}", runId, t.toString());
        }
    }

    private static boolean isCoordinatorOwnedTerminal(String type) {
        return AgentRun.STATUS_COMPLETED.equalsIgnoreCase(sansPrefix(type))
                || AgentRun.STATUS_FAILED.equalsIgnoreCase(sansPrefix(type))
                || AgentRun.STATUS_STOPPED.equalsIgnoreCase(sansPrefix(type))
                || AgentRun.STATUS_CHECKPOINTED.equalsIgnoreCase(sansPrefix(type))
                || "turn.stopped".equals(type);
    }

    private static String sansPrefix(String type) {
        if (type == null) return "";
        if (type.startsWith("run.")) return type.substring(4);
        return type;
    }

    // ------------------------------------------------------------------ //
    // Stop
    // ------------------------------------------------------------------ //

    /** Cooperatively stop a Run. Sets the cancel token and interrupts the
     *  worker. Idempotent per idempotency key. Guarantees the Run reaches a
     *  terminal STOPPED state even if the worker task was cancelled before it
     *  started (queue discard). */
    public synchronized StopOutcome stop(String runId, String idempotencyKey) {
        StopOutcome cached = stopOutcomes.get(idempotencyKey);
        if (cached != null) return cached;
        AtomicBoolean cancel = cancelTokens.get(runId);
        if (cancel == null) {
            StopOutcome out = StopOutcome.alreadyTerminal(runId);
            stopOutcomes.put(idempotencyKey, out);
            return out;
        }
        cancel.set(true);
        String sessionId = lookupSessionId(runId);
        java.util.concurrent.Future<?> f = futures.get(runId);
        if (f != null) f.cancel(true);
        // Give the worker a brief window to observe the cancel and persist the
        // STOPPED terminal itself. If the task was discarded before it ran
        // (cancel of a queued task), persist STOPPED directly so the Run can
        // never be stranded in QUEUED/RUNNING.
        boolean workerHandled = waitForTerminal(runId, 500);
        if (!workerHandled && sessionId != null) {
            try {
                store.completeRun(runId, sessionId, AgentRun.STATUS_STOPPED,
                        "", null, null,
                        new RunCheckpoint("cp-stop-" + UUID.randomUUID(), runId,
                                sessionId, "ws-agent-demo", 0, "USER_STOPPED",
                                Instant.now().toString()),
                        null, 0, 0, 0, 0, "USER_STOPPED", false, null);
            } catch (Throwable ignore) {
                // best-effort
            }
        }
        activeSessions.remove(sessionId);
        StopOutcome out = StopOutcome.accepted(runId);
        stopOutcomes.put(idempotencyKey, out);
        return out;
    }

    private String lookupSessionId(String runId) {
        return store.snapshot().runs().stream()
                .filter(r -> r.id().equals(runId))
                .map(AgentRun::sessionId).findFirst().orElse(null);
    }

    /** Poll the Store until the Run is terminal or the timeout elapses. */
    private boolean waitForTerminal(String runId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AgentRun r = store.snapshot().runs().stream()
                    .filter(x -> x.id().equals(runId)).findFirst().orElse(null);
            if (r == null || isTerminal(r.status())) return r != null;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void stopRun(String runId, String sessionId, AgentRuntimeHarness.TurnExecution te) {
        RunCheckpoint cp = new RunCheckpoint("cp-stop-" + UUID.randomUUID(),
                runId, sessionId, "ws-agent-demo",
                te == null ? 0 : te.run().stepsUsed(),
                "USER_STOPPED", Instant.now().toString());
        // 第三轮 Issue C（中文要点）：STOPPED 的 run 【不得持久化任何 assistant 回复】——
        // 传【空】assistantContent，让 completeRun 不创建 AssistantTurn；modelUnavailable=false，
        // 让 UI 永不为用户停止显示 MODEL_UNAVAILABLE。harness 中途产出的模型/工具结果被【彻底丢弃】。
        store.completeRun(runId, sessionId, AgentRun.STATUS_STOPPED,
                "", null, null, cp, null,
                te == null ? 0 : te.run().stepsUsed(),
                te == null ? 0 : te.run().toolCallsUsed(), 0,
                te == null ? 0 : te.run().outputCharsUsed(),
                "USER_STOPPED", false, null);
        activeSessions.remove(sessionId);
    }

    // ------------------------------------------------------------------ //
    // Retry
    // ------------------------------------------------------------------ //

    /** Retry a turn: create a NEW runId, reuse the user content but NOT the
     *  old model result / budget usage. Idempotent per retry key. */
    public synchronized RetryOutcome retry(String originalTurnId, String sessionId,
                                           String content, String idempotencyKey,
                                           com.supertrader.demo.taskcenter.StrategySpecDraft activeDraft,
                                           com.supertrader.demo.taskcenter.TurnQuestion pendingQuestion,
                                           String actorRole) {
        String existing = retryKeys.get(idempotencyKey);
        if (existing != null) {
            return RetryOutcome.replay(existing);
        }
        // Persist a fresh QUEUED Run reusing the user content (no old model
        // result / budget usage). The Store handles the retry idempotency.
        String bodyHash = Integer.toHexString((sessionId + "|" + content).hashCode());
        AgentDemoStore.AcceptOutcome accepted = store.enqueueRetryRun(
                sessionId, content, originalTurnId, idempotencyKey, bodyHash);
        if (accepted.conflictCode() != null) {
            return RetryOutcome.rejected(accepted.conflictCode(), null);
        }
        String newRunId = accepted.runId();
        retryKeys.put(idempotencyKey, newRunId);
        SubmitOutcome sub = submit(accepted, sessionId, content, activeDraft,
                pendingQuestion, actorRole);
        if (sub.rejected()) {
            retryKeys.remove(idempotencyKey);
            return RetryOutcome.rejected(sub.errorCode(), newRunId);
        }
        return RetryOutcome.of(newRunId);
    }

    // ------------------------------------------------------------------ //
    // Lifecycle
    // ------------------------------------------------------------------ //

    /** Stop accepting new work; in-flight runs finish or are cancelled. */
    public synchronized void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Block until no runs are in-flight (for tests). */
    public void awaitIdle() {
        while (executor.getActiveCount() > 0 || executor.getQueue().size() > 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private void failRun(String runId, String sessionId, Throwable t) {
        try {
            store.completeRun(runId, sessionId, AgentRun.STATUS_FAILED,
                    "", null, null, null, null, 0, 0, 0, 0,
                    t.getClass().getSimpleName(), true, null);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    private static boolean isTerminal(String status) {
        return AgentRun.STATUS_COMPLETED.equals(status)
                || AgentRun.STATUS_FAILED.equals(status)
                || AgentRun.STATUS_CHECKPOINTED.equals(status)
                || AgentRun.STATUS_STOPPED.equals(status)
                || AgentRun.STATUS_CANCELLED.equals(status);
    }

    // ------------------------------------------------------------------ //
    // Outcome types
    // ------------------------------------------------------------------ //

    public static final class SubmitOutcome {
        private final boolean accepted;
        private final String errorCode;
        private final String runId;
        private SubmitOutcome(boolean accepted, String errorCode, String runId) {
            this.accepted = accepted; this.errorCode = errorCode; this.runId = runId;
        }
        static SubmitOutcome accepted(String runId) { return new SubmitOutcome(true, null, runId); }
        static SubmitOutcome rejected(String code, String runId) {
            return new SubmitOutcome(false, code, runId);
        }
        public boolean accepted() { return accepted; }
        public boolean rejected() { return !accepted; }
        public String errorCode() { return errorCode; }
        public String runId() { return runId; }
    }

    public static final class StopOutcome {
        private final boolean accepted;
        private final boolean alreadyTerminal;
        private final String runId;
        private StopOutcome(boolean accepted, boolean alreadyTerminal, String runId) {
            this.accepted = accepted; this.alreadyTerminal = alreadyTerminal; this.runId = runId;
        }
        static StopOutcome accepted(String runId) { return new StopOutcome(true, false, runId); }
        static StopOutcome alreadyTerminal(String runId) {
            return new StopOutcome(false, true, runId);
        }
        public boolean accepted() { return accepted; }
        public boolean alreadyTerminal() { return alreadyTerminal; }
        public String runId() { return runId; }
    }

    public static final class RetryOutcome {
        private final String newRunId;
        private final boolean rejected;
        private final String errorCode;
        private RetryOutcome(String newRunId, boolean rejected, String errorCode) {
            this.newRunId = newRunId; this.rejected = rejected; this.errorCode = errorCode;
        }
        static RetryOutcome of(String runId) { return new RetryOutcome(runId, false, null); }
        static RetryOutcome replay(String runId) { return new RetryOutcome(runId, false, null); }
        static RetryOutcome rejected(String code, String runId) {
            return new RetryOutcome(runId, true, code);
        }
        public String newRunId() { return newRunId; }
        public boolean rejected() { return rejected; }
        public String errorCode() { return errorCode; }
    }

    private static final class NamedDaemonFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger count = new AtomicInteger();
        NamedDaemonFactory(String prefix) { this.prefix = prefix; }
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
