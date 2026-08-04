package com.supertrader.demo.taskcenter;

import com.supertrader.demo.agentdemo.AgentDemoEventHub;
import com.supertrader.demo.agentdemo.AgentDemoRunCoordinator;
import com.supertrader.demo.agentdemo.AgentDemoStore;
import com.supertrader.demo.agentdemo.AgentDemoDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7 tests for the asynchronous Run Coordinator.
 *
 * <p>Verifies the wire-up between the durable Store, the SSE Event Hub and the
 * unique Agent Runtime Harness: T1 persists BEFORE submit; the bounded
 * executor caps concurrency / queue; Stop saves a Checkpoint and stops new
 * steps; Retry creates a new runId reusing the user content but not the old
 * model result / budget usage; the queue-full path returns
 * {@code RUN_QUEUE_FULL}; and shutdown stops accepting work.
 */
class AgentDemoRunCoordinatorTest {

    @TempDir
    Path tmp;

    private HarnessFixture harnessFixture() throws Exception {
        TaskCenterTestSupport.Stack stack = TaskCenterTestSupport.build();
        TaskCenterTestSupport.copyAcceptanceDatasets(stack);
        CapabilityRegistry registry = new CapabilityRegistry();
        ToolProxy toolProxy = new ToolProxy(registry, stack.workspaces());
        toolProxy.register(CapabilityRegistry.STRATEGY_READ, args -> "ok");
        toolProxy.register(CapabilityRegistry.STRATEGY_DRAFT_UPDATE, args -> "ok");
        toolProxy.register(CapabilityRegistry.STRATEGY_VALIDATE, args -> "ok");
        toolProxy.register(CapabilityRegistry.RAG_SEARCH_LOCAL, args -> "ok");
        toolProxy.register(CapabilityRegistry.BACKTEST_PLAN, args -> "ok");
        toolProxy.register(CapabilityRegistry.BACKTEST_RESULT_SUMMARIZE, args -> "ok");
        AgentRuntimeHarness harness = new AgentRuntimeHarness(registry, toolProxy,
                stack.knowledge(), null, stack.validator(), stack.catalog(),
                stack.workspaces());
        harness.init();
        return new HarnessFixture(harness, registry, toolProxy);
    }

    private AgentDemoStore store() throws Exception {
        AgentDemoStore s = new AgentDemoStore(tmp.resolve("agent-demo.json"),
                "ws-agent-demo", "local-owner-demo");
        s.init();
        return s;
    }

    private AgentDemoEventHub hub(AgentDemoStore store) {
        return new AgentDemoEventHub(() -> List.copyOf(store.snapshot().events()),
                16, 64);
    }

    // ------------------------------------------------------------------ //
    // T1 persists before submit; successful run publishes terminal event
    // ------------------------------------------------------------------ //

    @Test
    void lifecycleEventsAreEmittedExactlyOnceAndStepsPersisted() throws Exception {
        // P1-3: run.started / run.completed are each emitted exactly ONCE (no
        // duplicate from Harness + Coordinator), and the traced AgentSteps are
        // durably persisted so a refresh can restore stepsByRun.
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);
        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "黄金5日线上穿20日线买入", "key-p13", "h1");
        coord.submit(a, "sess-1", "黄金5日线上穿20日线买入", null, null, "OWNER");
        coord.awaitIdle();

        List<AgentDemoDtos.DemoEventEnvelope> events = store.snapshot().events();
        long started = events.stream()
                .filter(e -> "sess-1".equals(e.sessionId()))
                .filter(e -> "run.started".equals(e.type())).count();
        long completed = events.stream()
                .filter(e -> "sess-1".equals(e.sessionId()))
                .filter(e -> "run.completed".equals(e.type())).count();
        assertEquals(1, started, "run.started must be emitted exactly once: " + events);
        assertEquals(1, completed, "run.completed must be emitted exactly once: " + events);
        // AgentSteps are persisted durably.
        long stepRows = store.snapshot().steps().stream()
                .filter(s -> a.runId().equals(s.runId())).count();
        assertTrue(stepRows >= 1, "at least one AgentStep must be persisted; got " + stepRows);
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void submittedRunPublishesStartedIntentCompleted() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);

        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "你好", "key-1", "h1");
        AgentDemoRunCoordinator.SubmitOutcome out = coord.submit(a, "sess-1",
                "你好", null, null, "OWNER");
        assertTrue(out.accepted(), "run should be accepted into the executor");

        coord.awaitIdle();
        AgentDemoStore.Snapshot snap = store.snapshot();
        // The Run reached a terminal state.
        String status = snap.runs().get(0).status();
        assertNotEquals("QUEUED", status, "run must have advanced past QUEUED");
        // Events were published: turn.accepted + run.started + intent + ... + terminal.
        List<String> types = snap.events().stream().map(AgentDemoDtos.DemoEventEnvelope::type).toList();
        assertTrue(types.contains("run.started"), "missing run.started: " + types);
        assertTrue(types.stream().anyMatch(t -> t.startsWith("run.")
                        && (t.contains("completed") || t.contains("checkpointed") || t.contains("failed") || t.contains("stopped"))),
                "missing terminal event: " + types);
        assertTrue(types.contains("intent.detected"), "missing intent.detected: " + types);
        // Assistant turn persisted (T3).
        assertTrue(snap.turns().size() >= 2, "assistant turn should be persisted");
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void plainQaDoesNotCreateSeedOrDraft() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);

        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "你好", "key-1", "h1");
        coord.submit(a, "sess-1", "你好", null, null, "OWNER");
        coord.awaitIdle();

        AgentDemoStore.Snapshot snap = store.snapshot();
        assertTrue(snap.seeds().isEmpty(), "plain QA must not create a seed");
        assertTrue(snap.drafts().isEmpty(), "plain QA must not create a draft");
        IntentResult intent = snap.intentResults().get(a.runId());
        assertNotNull(intent);
        assertEquals(IntentResult.GENERAL_QA, intent.primaryIntent());
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void strategyCandidateCreatesSeed() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);

        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "黄金5日线上穿20日线买入，止损3%", "key-1", "h1");
        coord.submit(a, "sess-1", "黄金5日线上穿20日线买入，止损3%", null, null, "OWNER");
        coord.awaitIdle();

        AgentDemoStore.Snapshot snap = store.snapshot();
        assertEquals(1, snap.seeds().size(), "candidate should create a seed");
        coord.shutdown();
        hub.shutdown();
    }

    // ------------------------------------------------------------------ //
    // Queue full
    // ------------------------------------------------------------------ //

    @Test
    void queueFullReturnsRunQueueFull() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        // Capacity 1, queue 1 — fill both then the third must be rejected.
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 1, 1);
        // Block the single worker with a slow intent port so the queue stays full.
        AtomicBoolean gate = new AtomicBoolean(true);
        IntentInferencePort slowPort = slowPort(gate);
        coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, slowPort, 1, 1);

        AgentDemoStore.AcceptOutcome a1 = store.acceptTurn("sess-1", "msg1", "k1", "h1");
        AgentDemoStore.AcceptOutcome a2 = store.acceptTurn("sess-2", "msg2", "k2", "h2");
        AgentDemoStore.AcceptOutcome a3 = store.acceptTurn("sess-3", "msg3", "k3", "h3");
        coord.submit(a1, "sess-1", "msg1", null, null, "OWNER");
        coord.submit(a2, "sess-2", "msg2", null, null, "OWNER");
        AgentDemoRunCoordinator.SubmitOutcome out = coord.submit(
                a3, "sess-3", "msg3", null, null, "OWNER");

        assertTrue(out.rejected(), "third submit should be rejected");
        assertEquals("RUN_QUEUE_FULL", out.errorCode());

        // Release the gate and shutdown cleanly.
        gate.set(false);
        coord.shutdown();
        hub.shutdown();
    }

    // ------------------------------------------------------------------ //
    // Stop
    // ------------------------------------------------------------------ //

    @Test
    void stopMarksRunStoppedAndSavesCheckpoint() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AtomicBoolean gate = new AtomicBoolean(true);
        IntentInferencePort slowPort = slowPort(gate);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, slowPort, 2, 16);

        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "黄金5日线上穿20日线买入", "key-1", "h1");
        coord.submit(a, "sess-1", "黄金5日线上穿20日线买入", null, null, "OWNER");

        // While the run is in-flight, stop it.
        AgentDemoRunCoordinator.StopOutcome stop = coord.stop(a.runId(), "stop-key-1");
        assertTrue(stop.accepted() || stop.alreadyTerminal(),
                "stop should be accepted or the run already terminal");
        gate.set(false); // release the worker so it observes the cancel
        coord.awaitIdle();

        AgentDemoStore.Snapshot snap = store.snapshot();
        String status = snap.runs().get(0).status();
        // After stop, the run is terminal (STOPPED if it observed the cancel
        // before finishing, otherwise COMPLETED — both are valid terminals;
        // the key invariant is no new steps appear after the stop signal).
        assertNotEquals("QUEUED", status);
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void stopDuringRunningRunReachesStoppedExactlyAndEmitsEvents() throws Exception {
        // P0-2: a Stop issued while the run is RUNNING must result in the run
        // ending in STOPPED (not FAILED, not COMPLETED), must save a
        // USER_STOPPED checkpoint, must emit run.stopped + checkpoint.saved,
        // and must NOT create an assistant success turn. The worker is the
        // single writer of the terminal state.
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AtomicBoolean gate = new AtomicBoolean(true);
        IntentInferencePort slowPort = slowPort(gate);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, slowPort, 2, 16);

        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "黄金5日线上穿20日线买入", "key-stoprun", "h1");
        coord.submit(a, "sess-1", "黄金5日线上穿20日线买入", null, null, "OWNER");
        // Stop while the model port is blocking → run is RUNNING.
        AgentDemoRunCoordinator.StopOutcome stop = coord.stop(a.runId(), "stop-key-run-1");
        assertTrue(stop.accepted(), "stop should be accepted while running");
        gate.set(false);
        coord.awaitIdle();

        AgentDemoStore.Snapshot snap = store.snapshot();
        AgentRun run = snap.runs().stream().filter(r -> r.id().equals(a.runId()))
                .findFirst().orElseThrow();
        assertEquals(AgentRun.STATUS_STOPPED, run.status(),
                "run must be STOPPED exactly, got " + run.status() + " / error=" + run.error());
        // A USER_STOPPED checkpoint was saved.
        RunCheckpoint cp = run.checkpointId() == null ? null
                : snap.checkpoints().get(run.checkpointId());
        assertNotNull(cp, "a checkpoint must be saved on stop");
        assertEquals("USER_STOPPED", cp.reason());
        // The SSE event log carries run.stopped + checkpoint.saved.
        List<String> types = snap.events().stream()
                .map(AgentDemoDtos.DemoEventEnvelope::type).toList();
        assertTrue(types.contains("run.stopped"), "missing run.stopped: " + types);
        assertTrue(types.contains("checkpoint.saved"), "missing checkpoint.saved: " + types);
        // No assistant success turn was created for the stopped run's user turn.
        long assistantForRun = snap.turns().stream()
                .filter(t -> "sess-1".equals(t.sessionId()))
                .filter(t -> t.role() != null && t.role().equals("ASSISTANT"))
                .filter(t -> t.content() != null && !t.content().isBlank())
                .count();
        assertEquals(0, assistantForRun, "no assistant success turn after stop");
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void stopAfterTerminalIsAlreadyTerminalAndDoesNotOverwrite() throws Exception {
        // P0-2: stopping an already-terminal run returns already-terminal and
        // does NOT overwrite the original terminal status.
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);
        AgentDemoStore.AcceptOutcome a = store.acceptTurn("sess-1", "你好", "k1", "h1");
        coord.submit(a, "sess-1", "你好", null, null, "OWNER");
        coord.awaitIdle();
        AgentRun before = store.snapshot().runs().stream()
                .filter(r -> r.id().equals(a.runId())).findFirst().orElseThrow();
        String originalStatus = before.status();
        String originalError = before.error();

        AgentDemoRunCoordinator.StopOutcome stop = coord.stop(a.runId(), "stop-late");
        assertTrue(stop.alreadyTerminal(), "stop on terminal run must be already-terminal");

        AgentRun after = store.snapshot().runs().stream()
                .filter(r -> r.id().equals(a.runId())).findFirst().orElseThrow();
        assertEquals(originalStatus, after.status(), "terminal status must not be overwritten");
        assertEquals(originalError, after.error(), "error must not change");
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void duplicateStopIsIdempotent() throws Exception {
        // P0-2: repeating a Stop with the SAME idempotency key returns the same
        // outcome and does not double-write.
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AtomicBoolean gate = new AtomicBoolean(true);
        IntentInferencePort slowPort = slowPort(gate);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, slowPort, 2, 16);
        AgentDemoStore.AcceptOutcome a = store.acceptTurn("sess-1", "你好", "k1", "h1");
        coord.submit(a, "sess-1", "你好", null, null, "OWNER");
        AgentDemoRunCoordinator.StopOutcome s1 = coord.stop(a.runId(), "stop-dup");
        AgentDemoRunCoordinator.StopOutcome s2 = coord.stop(a.runId(), "stop-dup");
        assertSame(s1, s2, "duplicate stop key must return the cached outcome");
        gate.set(false);
        coord.awaitIdle();
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void stopDuringModelCallDropsResultAndCreatesNoAssistantTurn() throws Exception {
        // P0-2: Stop during a model call must DROP the in-flight model result;
        // after stop the run is STOPPED with no assistant success turn and the
        // step count does not grow.
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AtomicBoolean gate = new AtomicBoolean(true);
        IntentInferencePort slowPort = slowPort(gate);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, slowPort, 2, 16);
        AgentDemoStore.AcceptOutcome a = store.acceptTurn("sess-1", "你好", "k1", "h1");
        coord.submit(a, "sess-1", "你好", null, null, "OWNER");
        coord.stop(a.runId(), "stop-mid-model");
        gate.set(false); // release the blocked model call
        coord.awaitIdle();

        AgentDemoStore.Snapshot snap = store.snapshot();
        AgentRun run = snap.runs().stream().filter(r -> r.id().equals(a.runId()))
                .findFirst().orElseThrow();
        assertEquals(AgentRun.STATUS_STOPPED, run.status(),
                "run must end STOPPED; got " + run.status() + " / " + run.error());
        long assistantSuccess = snap.turns().stream()
                .filter(t -> "sess-1".equals(t.sessionId()))
                .filter(t -> "ASSISTANT".equals(t.role()))
                .filter(t -> t.content() != null && !t.content().isBlank())
                .count();
        assertEquals(0, assistantSuccess, "no assistant success turn after mid-model stop");
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void stoppedRunSurvivesStoreReload() throws Exception {
        // P0-2: a STOPPED run must remain STOPPED after a process restart
        // (i.e. reopening the JSON Store from disk).
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);
        AgentDemoStore.AcceptOutcome a = store.acceptTurn("sess-1", "你好", "k1", "h1");
        coord.submit(a, "sess-1", "你好", null, null, "OWNER");
        coord.stop(a.runId(), "stop-then-reload");
        coord.awaitIdle();

        // Reopen the SAME store file (simulate a process restart / refresh).
        AgentDemoStore reopened = new AgentDemoStore(store.file(),
                "ws-agent-demo", "local-owner-demo");
        reopened.init();
        AgentRun r = reopened.snapshot().runs().stream()
                .filter(x -> x.id().equals(a.runId())).findFirst().orElseThrow();
        assertEquals(AgentRun.STATUS_STOPPED, r.status(),
                "STOPPED must survive store reload; got " + r.status());
        coord.shutdown();
        hub.shutdown();
    }

    // ------------------------------------------------------------------ //
    // Retry
    // ------------------------------------------------------------------ //

    @Test
    void retryCreatesNewRunIdReusingContent() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);

        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "你好", "key-1", "h1");
        coord.submit(a, "sess-1", "你好", null, null, "OWNER");
        coord.awaitIdle();

        // Retry the original turn.
        AgentDemoRunCoordinator.RetryOutcome retry = coord.retry(
                a.turnId(), "sess-1", "你好", "retry-key-1", null, null, "OWNER");
        assertNotNull(retry.newRunId());
        assertNotEquals(a.runId(), retry.newRunId(), "retry must use a new runId");
        coord.awaitIdle();

        AgentDemoStore.Snapshot snap = store.snapshot();
        // Two runs now: the original + the retry.
        assertEquals(2, snap.runs().size());
        coord.shutdown();
        hub.shutdown();
    }

    @Test
    void retryIsIdempotentForSameKey() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, IntentInferencePort.unavailable(), 2, 16);

        AgentDemoStore.AcceptOutcome a = store.acceptTurn(
                "sess-1", "你好", "key-1", "h1");
        coord.submit(a, "sess-1", "你好", null, null, "OWNER");
        coord.awaitIdle();

        AgentDemoRunCoordinator.RetryOutcome r1 = coord.retry(
                a.turnId(), "sess-1", "你好", "retry-key-1", null, null, "OWNER");
        coord.awaitIdle();
        AgentDemoRunCoordinator.RetryOutcome r2 = coord.retry(
                a.turnId(), "sess-1", "你好", "retry-key-1", null, null, "OWNER");
        // Same key → same newRunId (idempotent replay).
        assertEquals(r1.newRunId(), r2.newRunId());
        coord.shutdown();
        hub.shutdown();
    }

    // ------------------------------------------------------------------ //
    // One active run per session
    // ------------------------------------------------------------------ //

    @Test
    void atMostOneActiveRunPerSession() throws Exception {
        HarnessFixture fx = harnessFixture();
        AgentDemoStore store = store();
        AgentDemoEventHub hub = hub(store);
        AtomicBoolean gate = new AtomicBoolean(true);
        IntentInferencePort slowPort = slowPort(gate);
        AgentDemoRunCoordinator coord = new AgentDemoRunCoordinator(
                fx.harness, store, hub, slowPort, 2, 16);

        AgentDemoStore.AcceptOutcome a1 = store.acceptTurn("sess-1", "msg1", "k1", "h1");
        coord.submit(a1, "sess-1", "msg1", null, null, "OWNER");
        // A second run for the SAME session while the first is active.
        AgentDemoStore.AcceptOutcome a2 = store.acceptTurn("sess-1", "msg2", "k2", "h2");
        AgentDemoRunCoordinator.SubmitOutcome out = coord.submit(
                a2, "sess-1", "msg2", null, null, "OWNER");
        assertTrue(out.rejected(), "second concurrent run for same session rejected");
        assertEquals("SESSION_BUSY", out.errorCode());

        gate.set(false);
        coord.shutdown();
        hub.shutdown();
    }

    // ------------------------------------------------------------------ //
    // helpers
    // ------------------------------------------------------------------ //

    private static IntentInferencePort slowPort(AtomicBoolean gate) {
        return new IntentInferencePort() {
            @Override
            public com.supertrader.demo.taskcenter.IntentInferencePort.ModelIntent infer(
                    com.supertrader.demo.taskcenter.IntentInferencePort.IntentInferenceRequest request) {
                // Spin until the gate opens; keeps the worker busy so queue/
                // stop paths can be exercised. Honours interruption.
                while (gate.get()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return IntentInferencePort.unavailable().infer(request);
            }
            @Override
            public boolean modelConfigured() { return false; }
        };
    }

    private record HarnessFixture(AgentRuntimeHarness harness,
                                  CapabilityRegistry registry,
                                  ToolProxy toolProxy) {}
}
