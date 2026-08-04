package com.supertrader.demo.agentdemo;

import com.supertrader.demo.taskcenter.AgentRun;
import com.supertrader.demo.taskcenter.AgentStep;
import com.supertrader.demo.taskcenter.IntentResult;
import com.supertrader.demo.taskcenter.RunBudget;
import com.supertrader.demo.taskcenter.RunCheckpoint;
import com.supertrader.demo.taskcenter.StrategySeed;
import com.supertrader.demo.taskcenter.StrategySpecDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5 tests for the atomic Demo JSON Store + durable event outbox.
 *
 * <p>Verifies the T1 / T2 / T3 transactional methods, idempotency replay /
 * conflict, recovery after restart, corruption quarantine, and the invariant
 * that no credential-shaped field is ever persisted.
 */
class AgentDemoStoreTest {

    private static final String WS = "ws-agent-demo";
    private static final String OWNER = "local-owner-demo";

    @TempDir
    Path tmp;

    private AgentDemoStore store() throws Exception {
        return store(tmp.resolve("agent-demo.json"));
    }

    private AgentDemoStore store(Path file) throws Exception {
        AgentDemoStore s = new AgentDemoStore(file, WS, OWNER);
        s.init();
        return s;
    }

    private static RunBudget budget() {
        return new RunBudget(8, 12, 4000);
    }

    // ------------------------------------------------------------------ //
    // T1: acceptTurn
    // ------------------------------------------------------------------ //

    @Test
    void t1AtomicallyPersistsUserTurnQueuedRunAndAcceptedEvent() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));

        // The accepted response carries the durable event seq of turn.accepted.
        assertEquals("QUEUED", a.run().status());
        assertEquals("sess-1", a.run().sessionId());
        assertEquals(WS, a.run().workspaceId());
        assertNotNull(a.turnId());
        assertNotNull(a.runId());
        assertTrue(a.eventSeq() >= 1, "eventSeq should be monotonic >= 1");

        AgentDemoStore.Snapshot snap = s.snapshot();
        assertEquals(1, snap.turns().size());
        assertEquals("你好", snap.turns().get(0).content());
        assertEquals(1, snap.runs().size());
        assertEquals(AgentRun.STATUS_QUEUED, snap.runs().get(0).status());
        // turn.accepted event was appended to the durable outbox.
        assertEquals(1, snap.events().size());
        assertEquals("turn.accepted", snap.events().get(0).type());
        assertEquals(a.eventSeq(), snap.events().get(0).seq());
    }

    @Test
    void t1IdempotencySameKeySameBodyReplaysOriginalResponse() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome first = s.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));
        AgentDemoStore.AcceptOutcome replay = s.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));

        assertTrue(replay.replayed(), "same key+body must be a replay");
        assertEquals(first.turnId(), replay.turnId());
        assertEquals(first.runId(), replay.runId());
        assertEquals(first.eventSeq(), replay.eventSeq());
        // No duplicate turn / run / event was appended.
        AgentDemoStore.Snapshot snap = s.snapshot();
        assertEquals(1, snap.turns().size());
        assertEquals(1, snap.runs().size());
        assertEquals(1, snap.events().size());
    }

    @Test
    void t1IdempotencySameKeyDifferentBodyReturnsConflict() throws Exception {
        AgentDemoStore s = store();
        s.acceptTurn("sess-1", "你好", "key-1", hashOf("你好"));
        AgentDemoStore.AcceptOutcome conflict = s.acceptTurn(
                "sess-1", "different", "key-1", hashOf("different"));

        assertEquals(AgentDemoStore.AcceptOutcome.CONFLICT, conflict.status());
        assertNotNull(conflict.conflictCode());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.conflictCode());
    }

    @Test
    void t1CreatesSessionWhenAbsent() throws Exception {
        AgentDemoStore s = store();
        s.acceptTurn("sess-new", "你好", "key-1", hashOf("你好"));
        AgentDemoStore.Snapshot snap = s.snapshot();
        assertNotNull(snap.sessions().get("sess-new"));
        assertEquals(WS, snap.sessions().get("sess-new").workspaceId());
        assertEquals(OWNER, snap.sessions().get("sess-new").createdByMemberId());
    }

    // ------------------------------------------------------------------ //
    // T2: appendStepAndEvent
    // ------------------------------------------------------------------ //

    @Test
    void t2AppendsStepEventAndUpdatesRunAtomically() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "黄金5日线上穿20日线买入", "key-1", hashOf("黄金"));

        AgentStep step = new AgentStep("step-1", a.runId(), "sess-1", WS, 0,
                AgentStep.KIND_THINK, null, "intent=candidate", "thinking", false, 5, now());
        s.appendStepAndEvent(a.runId(), "sess-1", step,
                AgentRun.STATUS_RUNNING, 1, 0, 0, 50, null,
                eventPayload("step.started"));

        AgentDemoStore.Snapshot snap = s.snapshot();
        assertEquals(1, snap.steps().size());
        assertEquals("step-1", snap.steps().get(0).id());
        // Run status advanced to RUNNING.
        assertEquals(AgentRun.STATUS_RUNNING, snap.runs().get(0).status());
        // Event outbox grew (turn.accepted + step.started).
        assertEquals(2, snap.events().size());
        assertEquals("step.started", snap.events().get(1).type());
        assertTrue(snap.events().get(1).seq() > snap.events().get(0).seq());
    }

    @Test
    void eventsAreMonotonicAndUniquePerSession() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));
        for (int i = 0; i < 3; i++) {
            s.appendEvent("sess-1", a.runId(), "assistant.delta",
                    Map.of("seq", i));
        }
        List<AgentDemoDtos.DemoEventEnvelope> evs = s.eventsFor("sess-1", 0);
        assertEquals(4, evs.size());
        for (int i = 1; i < evs.size(); i++) {
            assertTrue(evs.get(i).seq() > evs.get(i - 1).seq(),
                    "seq must be strictly monotonic");
        }
    }

    @Test
    void eventsAfterReplaysOnlyThoseBeyondThreshold() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));
        s.appendEvent("sess-1", a.runId(), "step.started", Map.of());
        s.appendEvent("sess-1", a.runId(), "step.completed", Map.of());

        // Replay after the turn.accepted seq.
        List<AgentDemoDtos.DemoEventEnvelope> after = s.eventsFor("sess-1", a.eventSeq());
        assertEquals(2, after.size());
        assertEquals("step.started", after.get(0).type());
    }

    // ------------------------------------------------------------------ //
    // T3: completeRun
    // ------------------------------------------------------------------ //

    @Test
    void t3CompletesRunPersistsAssistantTurnIntentAndTerminalEvent() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "黄金5日线上穿20日线买入，止损3%", "key-1", hashOf("黄金"));

        IntentResult intent = new IntentResult(
                IntentResult.STRATEGY_CANDIDATE, List.of(IntentResult.STRATEGY_CANDIDATE),
                IntentResult.SPEECH_REQUEST, IntentResult.DOMAIN_STRATEGY,
                IntentResult.TARGET_STRATEGY_SEED, null, IntentResult.MUTATION_CREATE_SEED,
                IntentResult.AUTH_NOT_CONFIRMED, 0.0, 0.0, Map.of(),
                List.of(), List.of(), true, null, true);
        StrategySeed seed = new StrategySeed("seed-1", "黄金5日线上穿20日线买入",
                List.of("黄金"), null, null, "止损3%", IntentResult.STRATEGY_CANDIDATE,
                StrategySeed.STATUS_DETECTED, a.turnId(), now(), null);

        s.completeRun(a.runId(), "sess-1",
                AgentRun.STATUS_COMPLETED, "检测到策略候选", intent, seed, null,
                null, 2, 1, 0, 120, null, false, null);

        AgentDemoStore.Snapshot snap = s.snapshot();
        // Assistant turn persisted.
        assertEquals(2, snap.turns().size());
        assertEquals("ASSISTANT", snap.turns().get(1).role());
        // Intent result persisted.
        assertNotNull(snap.intentResults().get(a.runId()));
        // Seed persisted.
        assertEquals(1, snap.seeds().size());
        assertEquals("seed-1", snap.seeds().get(0).id());
        // Run is terminal.
        assertEquals(AgentRun.STATUS_COMPLETED, snap.runs().get(0).status());
        // Terminal event appended.
        AgentDemoDtos.DemoEventEnvelope last = snap.events().get(snap.events().size() - 1);
        assertEquals("run.completed", last.type());
    }

    // ------------------------------------------------------------------ //
    // Checkpoint
    // ------------------------------------------------------------------ //

    @Test
    void checkpointSavedOnBudgetExhaustion() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));
        RunCheckpoint cp = new RunCheckpoint("cp-1", a.runId(), "sess-1", WS, 8,
                RunCheckpoint.REASON_MAX_STEPS, now());
        s.completeRun(a.runId(), "sess-1",
                AgentRun.STATUS_CHECKPOINTED, "exhausted", null, null, cp,
                null, 8, 0, 0, 100, null, false, null);

        AgentDemoStore.Snapshot snap = s.snapshot();
        assertNotNull(snap.checkpoints().get("cp-1"));
        AgentDemoDtos.DemoEventEnvelope last = snap.events().get(snap.events().size() - 1);
        assertEquals("run.checkpointed", last.type());
    }

    // ------------------------------------------------------------------ //
    // Recovery + corruption quarantine
    // ------------------------------------------------------------------ //

    @Test
    void restartRecoversPersistedState() throws Exception {
        Path file = tmp.resolve("agent-demo.json");
        AgentDemoStore s1 = store(file);
        AgentDemoStore.AcceptOutcome a = s1.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));
        s1.close();

        AgentDemoStore s2 = store(file);
        AgentDemoStore.Snapshot snap = s2.snapshot();
        assertEquals(1, snap.turns().size());
        assertEquals("你好", snap.turns().get(0).content());
        assertEquals(1, snap.runs().size());
        assertEquals(a.runId(), snap.runs().get(0).id());
        assertEquals(1, snap.events().size());
        // Idempotency record recovered: replaying the same key returns CONFLICT
        // only on different body, and replays on same body.
        AgentDemoStore.AcceptOutcome replay = s2.acceptTurn(
                "sess-1", "你好", "key-1", hashOf("你好"));
        assertTrue(replay.replayed());
    }

    @Test
    void corruptFileIsQuarantinedAndStoreStartsEmpty() throws Exception {
        Path file = tmp.resolve("agent-demo.json");
        Files.writeString(file, "{ this is not valid json }}}");
        AgentDemoStore s = store(file);
        AgentDemoStore.Snapshot snap = s.snapshot();
        assertTrue(snap.turns().isEmpty());
        assertTrue(snap.runs().isEmpty());
        // The corrupt file was moved aside (quarantined).
        assertFalse(Files.exists(file));
    }

    @Test
    void missingFileStartsEmpty() throws Exception {
        Path file = tmp.resolve("absent.json");
        AgentDemoStore s = store(file);
        AgentDemoStore.Snapshot snap = s.snapshot();
        assertTrue(snap.sessions().isEmpty());
        assertTrue(snap.turns().isEmpty());
        // First write creates the file.
        s.acceptTurn("sess-1", "你好", "key-1", hashOf("你好"));
        assertTrue(Files.exists(file));
    }

    // ------------------------------------------------------------------ //
    // No sensitive fields persisted
    // ------------------------------------------------------------------ //

    @Test
    void persistedSnapshotHasNoCredentialFields() throws Exception {
        Path file = tmp.resolve("agent-demo.json");
        AgentDemoStore s = store(file);
        s.acceptTurn("sess-1", "你好", "key-1", hashOf("你好"));
        s.close();
        String json = Files.readString(file);
        // No credential markers anywhere in the snapshot.
        assertFalse(json.toLowerCase().contains("password"));
        assertFalse(json.toLowerCase().contains("authcode"));
        assertFalse(json.toLowerCase().contains("deepseek_api_key"));
        assertFalse(json.toLowerCase().contains("secret"));
        assertFalse(json.toLowerCase().contains("apikey"));
    }

    @Test
    void protocolVersionIsFixed() throws Exception {
        AgentDemoStore s = store();
        assertEquals("agent-demo-store.v1", s.protocol());
    }

    // ------------------------------------------------------------------ //
    // Seed decision / Draft (used by Task 8 service; verify store primitives)
    // ------------------------------------------------------------------ //

    @Test
    void seedDecisionConfirmCreatesDraft() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "黄金5日线上穿20日线买入", "key-1", hashOf("黄金"));
        StrategySeed seed = new StrategySeed("seed-1", "黄金候选", List.of("黄金"),
                null, null, null, IntentResult.STRATEGY_CANDIDATE,
                StrategySeed.STATUS_DETECTED, a.turnId(), now(), null);
        s.recordSeed(seed);

        AgentDemoStore.DraftOutcome draft = s.confirmSeed("seed-1",
                StrategySeed.STATUS_CONFIRMED, "draft-1");
        assertNotNull(draft.draft());
        assertEquals(StrategySpecDraft.STATUS_CO_CREATING, draft.draft().status());
        // Seed is now CONFIRMED.
        assertEquals(StrategySeed.STATUS_CONFIRMED,
                s.snapshot().seeds().get(0).status());
    }

    @Test
    void seedDecisionDiscussDoesNotCreateDraft() throws Exception {
        AgentDemoStore s = store();
        AgentDemoStore.AcceptOutcome a = s.acceptTurn(
                "sess-1", "黄金候选", "key-1", hashOf("黄金"));
        StrategySeed seed = new StrategySeed("seed-1", "黄金候选", List.of("黄金"),
                null, null, null, IntentResult.STRATEGY_CANDIDATE,
                StrategySeed.STATUS_DETECTED, a.turnId(), now(), null);
        s.recordSeed(seed);
        AgentDemoStore.DraftOutcome draft = s.confirmSeed("seed-1",
                StrategySeed.STATUS_DISCUSSED, null);
        assertNull(draft.draft());
        assertEquals(StrategySeed.STATUS_DISCUSSED,
                s.snapshot().seeds().get(0).status());
    }

    // ------------------------------------------------------------------ //
    // helpers
    // ------------------------------------------------------------------ //

    private static String hashOf(String body) {
        return Integer.toHexString(body.hashCode());
    }

    private static Map<String, Object> eventPayload(String type) {
        return Map.of("type", type);
    }

    private static String now() {
        return java.time.Instant.now().toString();
    }
}
