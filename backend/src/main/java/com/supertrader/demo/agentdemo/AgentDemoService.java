package com.supertrader.demo.agentdemo;

import com.supertrader.demo.taskcenter.AgentRun;
import com.supertrader.demo.taskcenter.AgentStep;
import com.supertrader.demo.taskcenter.ConversationSession;
import com.supertrader.demo.taskcenter.ConversationTurn;
import com.supertrader.demo.taskcenter.IntentResult;
import com.supertrader.demo.taskcenter.RunCheckpoint;
import com.supertrader.demo.taskcenter.StrategySeed;
import com.supertrader.demo.taskcenter.StrategySpecDraft;
import com.supertrader.demo.taskcenter.StrategySpecValidator;
import com.supertrader.demo.taskcenter.TaskCenterDtos;
import com.supertrader.demo.taskcenter.ToolProxy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Agent Demo application service (Task 8).
 *
 * <p>Orchestrates the Demo contract: guard → server context → T1 accept →
 * coordinator submit → 202. All model / capability calls go through the unique
 * {@link com.supertrader.demo.taskcenter.AgentRuntimeHarness} via the
 * {@link AgentDemoRunCoordinator}; this service never runs a second Agent
 * kernel. Session create / list / detail GET endpoints never trigger a run or
 * a model call.
 *
 * <p>Seed decisions: only {@link StrategySeed#STATUS_CONFIRMED} (CO_CREATE)
 * creates a Draft; {@link StrategySeed#STATUS_DISCUSSED} (DISCUSS) does not.
 * Draft patches apply deterministically (reusing the existing
 * {@link StrategySpecDraft} field helpers) and bump the optimistic-lock
 * version.
 */
@Service
public class AgentDemoService {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoService.class);

    private final AgentDemoStore store;
    private final AgentDemoEventHub hub;
    private final AgentDemoRunCoordinator coordinator;
    private final DemoSensitiveContentGuard guard;
    private final StrategySpecValidator validator;

    @Value("${app.agent-demo.owner-id:local-owner-demo}")
    private String ownerId;
    @Value("${app.agent-demo.workspace-id:ws-agent-demo}")
    private String workspaceId;

    public AgentDemoService(AgentDemoStore store, AgentDemoEventHub hub,
                            AgentDemoRunCoordinator coordinator,
                            DemoSensitiveContentGuard guard,
                            StrategySpecValidator validator) {
        this.store = store;
        this.hub = hub;
        this.coordinator = coordinator;
        this.guard = guard;
        this.validator = validator;
    }

    @PostConstruct
    void init() {
        store.init();
    }

    // ------------------------------------------------------------------ //
    // Sessions
    // ------------------------------------------------------------------ //

    public ConversationSession createSession(String title) {
        String id = "sess-" + UUID.randomUUID();
        String now = Instant.now().toString();
        ConversationSession s = new ConversationSession(id, workspaceId,
                title == null || title.isBlank() ? "新会话" : title,
                ConversationSession.STATUS_ACTIVE, ownerId, now, now);
        store.saveSession(s);
        return s;
    }

    public List<AgentDemoDtos.DemoSessionSummary> listSessions() {
        List<AgentDemoDtos.DemoSessionSummary> out = new ArrayList<>();
        for (ConversationSession s : store.snapshot().sessions().values()) {
            out.add(new AgentDemoDtos.DemoSessionSummary(s.id(), s.title(),
                    s.status(), s.createdAt(), store.lastEventSeq(s.id())));
        }
        return out;
    }

    public AgentDemoDtos.DemoSessionDetail sessionDetail(String sessionId) {
        AgentDemoStore.Snapshot snap = store.snapshot();
        ConversationSession s = snap.sessions().get(sessionId);
        if (s == null) throw new AgentDemoApiException("NOT_FOUND",
                "session not found: " + sessionId);

        List<AgentDemoDtos.DemoTurnView> turns = new ArrayList<>();
        List<AgentDemoDtos.DemoRunView> runs = new ArrayList<>();
        List<AgentDemoDtos.DemoStepView> steps = new ArrayList<>();
        List<AgentDemoDtos.DemoSeedView> seeds = new ArrayList<>();
        AgentDemoDtos.DemoDraftView activeDraft = null;

        for (ConversationTurn t : snap.turns()) {
            if (!sessionId.equals(t.sessionId())) continue;
            AgentDemoDtos.DemoSeedView seedView = t.seed() == null ? null
                    : new AgentDemoDtos.DemoSeedView(t.seed().id(), t.seed().summary(),
                            t.seed().status(), t.seed().sourceTurnId());
            turns.add(new AgentDemoDtos.DemoTurnView(t.id(), t.role(), t.content(),
                    t.intent(), t.modelUnavailable(), seedView, t.createdAt()));
        }
        for (AgentRun r : snap.runs()) {
            if (!sessionId.equals(r.sessionId())) continue;
            RunCheckpoint cp = r.checkpointId() == null ? null
                    : snap.checkpoints().get(r.checkpointId());
            // 第三轮 Issue B（中文要点）：从持久化的 IntentResult 把【reconcile 后的
            // 意图详情】带进 Run view（labels / authorization / calibratedConfidence /
            // requiresConfirmation）。这样前端 Inspector 可以【权威同步】——之前只暴露
            // intent 主标签，前端用 `prev.intent ?? match.intent` 短路，导致 Inspector
            // 持续显示陈旧的 GENERAL_QA，看不到 STRATEGY_CANDIDATE/REFINEMENT/EXECUTION。
            IntentResult ir = snap.intentResults().get(r.id());
            runs.add(new AgentDemoDtos.DemoRunView(r.id(), r.intent(),
                    ir == null ? null : ir.labels(),
                    ir == null ? null : ir.authorization(),
                    ir == null ? null : ir.calibratedConfidence(),
                    ir == null ? null : ir.requiresConfirmation(),
                    r.status(),
                    r.stepsUsed(), r.budget().maxSteps(), r.toolCallsUsed(),
                    r.budget().maxToolCalls(),
                    // P1-3: report real model-call usage. The harness makes up
                    // to two model calls per turn (intent inference + reply);
                    // when MODEL_UNAVAILABLE neither runs, so usage is 0.
                    r.modelUnavailable() ? 0 : 2, 4, r.outputCharsUsed(),
                    elapsedMs(r), r.modelUnavailable(), r.error(),
                    cp == null ? null : cp.reason(), r.createdAt()));
        }
        for (AgentStep st : snap.steps()) {
            if (!sessionId.equals(st.sessionId())) continue;
            steps.add(new AgentDemoDtos.DemoStepView(st.runId(), st.seq(), st.kind(),
                    st.capability(), st.output(), st.durationMs(), st.createdAt()));
        }
        for (StrategySeed seed : snap.seeds()) {
            String seedSession = seedSessionId(seed, snap);
            if (!sessionId.equals(seedSession)) continue;
            seeds.add(new AgentDemoDtos.DemoSeedView(seed.id(), seed.summary(),
                    seed.status(), seed.sourceTurnId()));
        }
        // The active draft is the CO_CREATING draft of the most recent seed.
        for (StrategySpecDraft d : snap.drafts().values()) {
            if (sessionId.equals(d.sessionId())
                    && StrategySpecDraft.STATUS_CO_CREATING.equals(d.status())) {
                activeDraft = toDraftView(d);
            }
        }

        return new AgentDemoDtos.DemoSessionDetail(s.id(), s.workspaceId(),
                s.title(), s.status(), turns, runs, steps, seeds, activeDraft,
                store.lastEventSeq(sessionId));
    }

    // ------------------------------------------------------------------ //
    // Turns: guard → server context → T1 → submit → 202
    // ------------------------------------------------------------------ //

    public AgentDemoDtos.AcceptedTurnResponse acceptTurn(String sessionId, String content,
                                                         String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AgentDemoApiException("IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required");
        }
        if (!store.snapshot().sessions().containsKey(sessionId)) {
            throw new AgentDemoApiException("NOT_FOUND", "session not found: " + sessionId);
        }
        DemoSensitiveContentGuard.Verdict v = guard.check(content);
        if (!v.accepted()) {
            throw new AgentDemoApiException(v.code(), v.message());
        }
        String bodyHash = Integer.toHexString((sessionId + "|" + content).hashCode());
        AgentDemoStore.AcceptOutcome a = store.acceptTurn(sessionId, content,
                idempotencyKey, bodyHash);
        if (a.conflictCode() != null) {
            throw new AgentDemoApiException(a.conflictCode(),
                    "idempotency conflict for key " + idempotencyKey);
        }
        // Submit to the coordinator (async). If the executor rejects, fail the
        // accepted Run honestly instead of losing the record.
        AgentDemoRunCoordinator.SubmitOutcome sub = coordinator.submit(a, sessionId,
                content, activeDraftFor(sessionId), pendingQuestionFor(sessionId),
                ownerId);
        if (sub.rejected()) {
            // The Run was accepted (T1) but could not be scheduled. Mark it
            // FAILED atomically — never silently drop it.
            store.completeRun(a.runId(), sessionId, AgentRun.STATUS_FAILED,
                    "", null, null, null, null, 0, 0, 0, 0,
                    sub.errorCode(), true, null);
            throw new AgentDemoApiException(sub.errorCode(),
                    "could not schedule run: " + sub.errorCode());
        }
        return new AgentDemoDtos.AcceptedTurnResponse(sessionId, a.turnId(),
                a.runId(), AgentRun.STATUS_QUEUED, a.eventSeq());
    }

    // ------------------------------------------------------------------ //
    // Stop
    // ------------------------------------------------------------------ //

    public AgentDemoDtos.StopResponse stop(String runId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AgentDemoApiException("IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required");
        }
        AgentRun run = store.snapshot().runs().stream()
                .filter(r -> r.id().equals(runId)).findFirst().orElse(null);
        if (run == null) {
            throw new AgentDemoApiException("NOT_FOUND", "run not found: " + runId);
        }
        AgentDemoRunCoordinator.StopOutcome out = coordinator.stop(runId, idempotencyKey);
        return new AgentDemoDtos.StopResponse(runId,
                out.alreadyTerminal() ? run.status() : AgentRun.STATUS_STOPPED,
                run.stepsUsed());
    }

    // ------------------------------------------------------------------ //
    // Retry
    // ------------------------------------------------------------------ //

    public AgentDemoRunCoordinator.RetryOutcome retry(String turnId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AgentDemoApiException("IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required");
        }
        ConversationTurn turn = store.snapshot().turns().stream()
                .filter(t -> t.id().equals(turnId)
                        && ConversationTurn.ROLE_USER.equals(t.role()))
                .findFirst().orElse(null);
        if (turn == null) {
            throw new AgentDemoApiException("NOT_FOUND", "turn not found: " + turnId);
        }
        return coordinator.retry(turnId, turn.sessionId(), turn.content(), idempotencyKey,
                activeDraftFor(turn.sessionId()), pendingQuestionFor(turn.sessionId()),
                ownerId);
    }

    // ------------------------------------------------------------------ //
    // Seed decision → Draft
    // ------------------------------------------------------------------ //

    public AgentDemoStore.DraftOutcome decideSeed(String seedId, String decision) {
        // Map the public decision vocabulary (CO_CREATE / DISCUSS) to the seed
        // status vocabulary (CONFIRMED / DISCUSSED).
        String status;
        if ("CO_CREATE".equals(decision)) {
            status = StrategySeed.STATUS_CONFIRMED;
        } else if ("DISCUSS".equals(decision)) {
            status = StrategySeed.STATUS_DISCUSSED;
        } else {
            throw new AgentDemoApiException("INVALID_STATE",
                    "decision must be CO_CREATE or DISCUSS");
        }
        AgentDemoStore.DraftOutcome out = store.confirmSeed(seedId, status, null);
        if (!out.found()) {
            throw new AgentDemoApiException("NOT_FOUND", "seed not found: " + seedId);
        }
        return out;
    }

    // ------------------------------------------------------------------ //
    // Draft patch (deterministic; reuses field helpers)
    // ------------------------------------------------------------------ //

    public AgentDemoDtos.DemoDraftView patchDraft(String draftId, int ifMatchVersion,
                                                  String field, String value,
                                                  String evidenceExcerpt) {
        StrategySpecDraft d = store.draft(draftId);
        if (d == null) {
            throw new AgentDemoApiException("NOT_FOUND", "draft not found: " + draftId);
        }
        int current = store.draftVersion(draftId);
        if (current != ifMatchVersion) {
            throw new AgentDemoApiException("OPTIMISTIC_LOCK_CONFLICT",
                    "draft version mismatch");
        }
        // P0-3: write the FieldEvidence referencing the source turn that the
        // correction came from. For a draft patch the natural source is the
        // most recent USER turn of the draft's session (the correcting user
        // message). Never report "updated" before the Store write succeeds —
        // applyDraftPatch throws on failure, so we only reach toDraftView when
        // the write is durable.
        String sourceTurnId = latestUserTurnId(d.sessionId());
        // Apply the field patch deterministically (no LLM).
        TaskCenterDtos.DraftPatch patch = singleFieldPatch(field, value);
        StrategySpecDraft updated = store.applyDraftPatch(draftId, patch, sourceTurnId);
        return toDraftView(updated);
    }

    /** The most recent USER turn id of a session (the source of a correction). */
    private String latestUserTurnId(String sessionId) {
        AgentDemoStore.Snapshot snap = store.snapshot();
        for (int i = snap.turns().size() - 1; i >= 0; i--) {
            ConversationTurn t = snap.turns().get(i);
            if (sessionId.equals(t.sessionId())
                    && ConversationTurn.ROLE_USER.equals(t.role())) {
                return t.id();
            }
        }
        return null;
    }

    /** Validate a Draft with the deterministic Validator (no LLM). */
    public Object validateDraft(String draftId) {
        StrategySpecDraft d = store.draft(draftId);
        if (d == null) {
            throw new AgentDemoApiException("NOT_FOUND", "draft not found: " + draftId);
        }
        return validator.validate(d);
    }

    // ------------------------------------------------------------------ //
    // SSE
    // ------------------------------------------------------------------ //

    public List<AgentDemoDtos.DemoEventEnvelope> events(String sessionId, long after) {
        if (!store.snapshot().sessions().containsKey(sessionId)) {
            throw new AgentDemoApiException("NOT_FOUND", "session not found: " + sessionId);
        }
        return store.eventsFor(sessionId, after);
    }

    public AgentDemoEventHub eventHub() {
        return hub;
    }

    /** Block until the session's run is idle (for tests / synchronous checks). */
    public void awaitIdle(String sessionId) {
        coordinator.awaitIdle();
    }

    /** Reset the Demo store to empty (test-only; clears all persisted state). */
    public void resetStoreForTest() {
        store.resetForTest();
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private StrategySpecDraft activeDraftFor(String sessionId) {
        for (StrategySpecDraft d : store.snapshot().drafts().values()) {
            if (sessionId.equals(d.sessionId())
                    && StrategySpecDraft.STATUS_CO_CREATING.equals(d.status())) {
                return d;
            }
        }
        return null;
    }

    private com.supertrader.demo.taskcenter.TurnQuestion pendingQuestionFor(String sessionId) {
        // The pending question is the latest assistant turn's question.
        AgentDemoStore.Snapshot snap = store.snapshot();
        for (int i = snap.turns().size() - 1; i >= 0; i--) {
            ConversationTurn t = snap.turns().get(i);
            if (sessionId.equals(t.sessionId()) && t.question() != null) {
                return t.question();
            }
        }
        return null;
    }

    /** Resolve the session a seed belongs to (via its source turn). */
    private String seedSessionId(StrategySeed seed, AgentDemoStore.Snapshot snap) {
        if (seed.sourceTurnId() == null) return null;
        return snap.turns().stream()
                .filter(t -> t.id().equals(seed.sourceTurnId()))
                .map(ConversationTurn::sessionId).findFirst().orElse(null);
    }

    private long elapsedMs(AgentRun r) {
        try {
            long start = Instant.parse(r.createdAt()).toEpochMilli();
            long end = r.finishedAt() == null ? System.currentTimeMillis()
                    : Instant.parse(r.finishedAt()).toEpochMilli();
            return Math.max(0, end - start);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Expose the Draft → view mapping (the controller uses it for seed decisions). */
    public AgentDemoDtos.DemoDraftView toView(StrategySpecDraft d) {
        return d == null ? null : toDraftView(d);
    }

    private AgentDemoDtos.DemoDraftView toDraftView(StrategySpecDraft d) {
        List<AgentDemoDtos.DemoEvidenceView> ev = new ArrayList<>();
        if (d.evidence() != null) {
            for (var e : d.evidence()) {
                // Demo evidence is always Mock-labeled (FIXTURE).
                ev.add(new AgentDemoDtos.DemoEvidenceView(e.field(), "FIXTURE",
                        e.sourceTurnId(), e.value(), false));
            }
        }
        // Deterministic nextQuestion via the shared domain helper (one curated
        // question per round), not a generic "missingFields.get(0)" string.
        String nextQuestion = null;
        com.supertrader.demo.taskcenter.TurnQuestion q =
                com.supertrader.demo.taskcenter.SpecFieldHelpers.nextQuestion(d);
        if (q != null) nextQuestion = q.prompt();
        boolean frozen = StrategySpecDraft.STATUS_FROZEN.equals(d.status());
        int version = store.draftVersion(d.id());
        return new AgentDemoDtos.DemoDraftView(d.id(), d.status(), version,
                d.completeness(), d.missingFields(), draftFieldsMap(d), ev,
                nextQuestion, frozen);
    }

    private java.util.Map<String, Object> draftFieldsMap(StrategySpecDraft d) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (d.name() != null) m.put("name", d.name());
        if (d.instruments() != null) m.put("instruments", d.instruments());
        if (d.timeframe() != null) m.put("timeframe", d.timeframe());
        if (d.entryCondition() != null) m.put("entryCondition", d.entryCondition());
        if (d.exitCondition() != null) m.put("exitCondition", d.exitCondition());
        if (d.riskLimits() != null) m.put("riskLimits", d.riskLimits());
        if (d.backtestAssumptions() != null) m.put("backtestAssumptions", d.backtestAssumptions());
        // Expose the structured parameters (fastWindow / slowWindow / positionSize
        // / stopLossPct / feeBps / slippageBps) so the UI and tests can observe
        // the real scalar values, not just the hint strings. SpecParameters is a
        // Jackson-annotated record; it serializes directly.
        if (d.parameters() != null) {
            m.put("parameters", d.parameters());
        }
        return m;
    }

    private TaskCenterDtos.DraftPatch singleFieldPatch(String field, String value) {
        // Map a single field/value to the existing DraftPatch record.
        return switch (field) {
            case "name" -> new TaskCenterDtos.DraftPatch(value, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
            case "entryCondition" -> new TaskCenterDtos.DraftPatch(null, null, null, null,
                    null, null, null, null, null, value, null, null, null, null, null);
            case "exitCondition" -> new TaskCenterDtos.DraftPatch(null, null, null, null,
                    null, null, null, null, null, null, value, null, null, null, null);
            case "riskLimits" -> new TaskCenterDtos.DraftPatch(null, null, null, null,
                    null, null, null, null, null, null, null, value, null, null, null);
            case "fastWindow" -> new TaskCenterDtos.DraftPatch(null, null, null,
                    Integer.valueOf(value), null, null, null, null, null, null, null,
                    null, null, null, null);
            case "slowWindow" -> new TaskCenterDtos.DraftPatch(null, null, null, null,
                    Integer.valueOf(value), null, null, null, null, null, null,
                    null, null, null, null);
            case "positionSize" -> new TaskCenterDtos.DraftPatch(null, null, null, null,
                    null, Double.valueOf(value), null, null, null, null, null,
                    null, null, null, null);
            case "stopLossPct" -> new TaskCenterDtos.DraftPatch(null, null, null, null,
                    null, null, Double.valueOf(value), null, null, null, null,
                    null, null, null, null);
            default -> throw new AgentDemoApiException("INVALID_STATE",
                    "unsupported field: " + field);
        };
    }
}
