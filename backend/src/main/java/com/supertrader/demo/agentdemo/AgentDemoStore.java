package com.supertrader.demo.agentdemo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supertrader.demo.taskcenter.AgentRun;
import com.supertrader.demo.taskcenter.AgentStep;
import com.supertrader.demo.taskcenter.ConversationSession;
import com.supertrader.demo.taskcenter.ConversationTurn;
import com.supertrader.demo.taskcenter.FieldEvidence;
import com.supertrader.demo.taskcenter.IntentResult;
import com.supertrader.demo.taskcenter.RunBudget;
import com.supertrader.demo.taskcenter.RunCheckpoint;
import com.supertrader.demo.taskcenter.SpecFieldHelpers;
import com.supertrader.demo.taskcenter.SpecParameters;
import com.supertrader.demo.taskcenter.StrategySeed;
import com.supertrader.demo.taskcenter.StrategySpecDraft;
import com.supertrader.demo.taskcenter.TaskCenterDtos.DraftPatch;
import com.supertrader.demo.taskcenter.TurnQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Agent Demo atomic JSON Store + durable event outbox (Task 5).
 *
 * <p>This is the Demo's persistence Adapter: it stands in for the formal
 * MySQL + Flyway stack (which is Mock in the Demo — design §11.2). It honours
 * the same transactional intent as the formal T1 / T2 / T3 boundary:
 * <ul>
 *   <li><b>T1 acceptTurn</b> — atomically persists the UserTurn, a QUEUED
 *       AgentRun, the {@code turn.accepted} event and the idempotency record.
 *       If the durable write fails the run is NEVER scheduled (fail-closed).</li>
 *   <li><b>T2 appendStepAndEvent / appendEvent</b> — atomically persists a
 *       traced Step (optional) + Run usage update + a durable SSE event.</li>
 *   <li><b>T3 completeRun</b> — atomically persists the AssistantTurn, the
 *       IntentResult, an optional Seed / Draft diff, an optional Checkpoint,
 *       the terminal Run status and the terminal SSE event.</li>
 * </ul>
 *
 * <p>Durability is achieved with a same-directory temp file, an exclusive
 * {@link FileLock}, {@code fsync}-equivalent force, and an atomic
 * {@link Files#move}. A missing / empty file starts empty; a corrupt file is
 * quarantined (renamed aside) and the Store starts empty — startup NEVER
 * depends on the file and NEVER throws on a corrupt snapshot.
 *
 * <p>The Store only ever holds whitelisted NON-SENSITIVE fields. There is
 * deliberately NO credential, account, order/cancel or trading field anywhere
 * in the snapshot; credential-shaped content is masked by the harness before it
 * reaches the Store. The fixed protocol is {@link #PROTOCOL}.
 */
public final class AgentDemoStore {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String PROTOCOL = "agent-demo-store.v1";

    private final Path file;
    private final String workspaceId;
    private final String ownerId;
    private final AtomicLong globalEventSeq = new AtomicLong(0);

    private volatile SnapshotData data;

    public AgentDemoStore(Path file, String workspaceId, String ownerId) {
        this.file = file;
        this.workspaceId = workspaceId;
        this.ownerId = ownerId;
    }

    /** The on-disk store file (used by tests to simulate a process restart). */
    public Path file() {
        return file;
    }

    /** Load (or start empty / quarantine). Idempotent. */
    public synchronized void init() {
        loadOrReset();
    }

    /** Flush + release resources. The lock is per-operation so this is a no-op
     *  apart from a final flush. */
    public synchronized void close() {
        flush();
    }

    public String protocol() {
        return PROTOCOL;
    }

    /** Reset the Store to empty and overwrite the file (test isolation). */
    public synchronized void resetForTest() {
        this.data = SnapshotData.empty();
        this.globalEventSeq.set(0);
        flushOrThrow();
    }

    // ------------------------------------------------------------------ //
    // T1: acceptTurn
    // ------------------------------------------------------------------ //

    /**
     * T1: atomically persist the UserTurn, a QUEUED Run, the
     * {@code turn.accepted} event and the idempotency record. Returns the
     * accepted response (or a replay / conflict outcome).
     *
     * @param sessionId       the owning session (created if absent)
     * @param content         the validated user message
     * @param idempotencyKey  the client {@code Idempotency-Key}
     * @param bodyHash        a stable hash of the request body
     */
    public synchronized AcceptOutcome acceptTurn(String sessionId, String content,
                                                 String idempotencyKey, String bodyHash) {
        ensureInitialized();
        // Idempotency: workspace + route(turns) + key.
        IdempotencyRecord existing = data.idempotency.get(idempotencyKey);
        if (existing != null) {
            if (existing.bodyHash().equals(bodyHash)) {
                return AcceptOutcome.replay(existing.turnId(), existing.runId(),
                        existing.eventSeq());
            }
            return AcceptOutcome.conflict();
        }

        String now = Instant.now().toString();
        String turnId = "turn-" + UUID.randomUUID();
        String runId = "run-" + UUID.randomUUID();
        // Ensure the session exists.
        if (!data.sessions.containsKey(sessionId)) {
            ConversationSession s = new ConversationSession(
                    sessionId, workspaceId, defaultTitle(content),
                    ConversationSession.STATUS_ACTIVE, ownerId, now, now);
            data.sessions.put(sessionId, s);
        }
        RunBudget budget = new RunBudget(8, 12, 4000);
        AgentRun run = new AgentRun(runId, sessionId, workspaceId,
                IntentResult.GENERAL_QA, AgentRun.STATUS_QUEUED, budget,
                0, 0, 0, null, false, null, now, null);
        ConversationTurn userTurn = new ConversationTurn(turnId, sessionId,
                workspaceId, ConversationTurn.ROLE_USER, content, null,
                false, null, null, now);

        long seq = nextEventSeq(sessionId);
        AgentDemoDtos.DemoEventEnvelope accepted = new AgentDemoDtos.DemoEventEnvelope(
                seq, "evt-" + UUID.randomUUID(), sessionId, runId,
                "turn.accepted", now,
                Map.of("turnId", turnId, "runId", runId, "status", "QUEUED"));

        data.turns.add(userTurn);
        data.runs.add(run);
        data.events.add(accepted);
        data.idempotency.put(idempotencyKey,
                new IdempotencyRecord(workspaceId, "turns", idempotencyKey,
                        bodyHash, turnId, runId, seq));

        flushOrThrow();
        return AcceptOutcome.ok(turnId, runId, seq, run);
    }

    /**
     * T1-equivalent for a Retry: persist a fresh QUEUED Run that reuses the
     * user content of an earlier turn (without re-creating a duplicate user
     * turn or replaying the old model result / budget). Returns the accepted
     * response. Idempotent on the retry key.
     */
    public synchronized AcceptOutcome enqueueRetryRun(String sessionId, String content,
                                                      String originalTurnId,
                                                      String retryIdempotencyKey,
                                                      String bodyHash) {
        ensureInitialized();
        IdempotencyRecord existing = data.idempotency.get(retryIdempotencyKey);
        if (existing != null) {
            if (existing.bodyHash().equals(bodyHash)) {
                return AcceptOutcome.replay(existing.turnId(), existing.runId(),
                        existing.eventSeq());
            }
            return AcceptOutcome.conflict();
        }
        String now = Instant.now().toString();
        String runId = "run-" + UUID.randomUUID();
        if (!data.sessions.containsKey(sessionId)) {
            data.sessions.put(sessionId, new ConversationSession(sessionId, workspaceId,
                    defaultTitle(content), ConversationSession.STATUS_ACTIVE,
                    ownerId, now, now));
        }
        RunBudget budget = new RunBudget(8, 12, 4000);
        AgentRun run = new AgentRun(runId, sessionId, workspaceId,
                IntentResult.GENERAL_QA, AgentRun.STATUS_QUEUED, budget,
                0, 0, 0, null, false, null, now, null);
        long seq = nextEventSeq(sessionId);
        AgentDemoDtos.DemoEventEnvelope accepted = new AgentDemoDtos.DemoEventEnvelope(
                seq, "evt-" + UUID.randomUUID(), sessionId, runId,
                "turn.accepted", now,
                Map.of("turnId", originalTurnId, "runId", runId,
                        "status", "QUEUED", "retry", true));
        data.runs.add(run);
        data.events.add(accepted);
        data.idempotency.put(retryIdempotencyKey,
                new IdempotencyRecord(workspaceId, "retry", retryIdempotencyKey,
                        bodyHash, originalTurnId, runId, seq));
        flushOrThrow();
        return AcceptOutcome.ok(originalTurnId, runId, seq, run);
    }

    // ------------------------------------------------------------------ //
    // T2: appendStepAndEvent / appendEvent
    // ------------------------------------------------------------------ //

    /** T2: persist a traced Step + Run usage update + a durable event. */
    public synchronized void appendStepAndEvent(String runId, String sessionId,
                                                AgentStep step, String runStatus,
                                                int stepsUsed, int toolCallsUsed,
                                                int modelCallsUsed, int outputChars,
                                                String checkpointId,
                                                Map<String, Object> payload) {
        ensureInitialized();
        updateRunUsage(runId, runStatus, stepsUsed, toolCallsUsed, modelCallsUsed,
                outputChars, checkpointId, null, null);
        if (step != null) {
            data.steps.add(step);
        }
        appendEventInternal(sessionId, runId, (String) payload.get("type"), payload);
        flushOrThrow();
    }

    /** Append a durable event (no step). */
    public synchronized void appendEvent(String sessionId, String runId, String type,
                                         Map<String, Object> payload) {
        ensureInitialized();
        appendEventInternal(sessionId, runId, type, payload);
        flushOrThrow();
    }

    private void appendEventInternal(String sessionId, String runId, String type,
                                     Map<String, Object> payload) {
        String now = Instant.now().toString();
        long seq = nextEventSeq(sessionId);
        Map<String, Object> safePayload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        if (type != null) safePayload.put("type", type);
        AgentDemoDtos.DemoEventEnvelope env = new AgentDemoDtos.DemoEventEnvelope(
                seq, "evt-" + UUID.randomUUID(), sessionId, runId,
                type == null ? "event" : type, now, safePayload);
        data.events.add(env);
    }

    // ------------------------------------------------------------------ //
    // T3: completeRun
    // ------------------------------------------------------------------ //

    /**
     * T3: atomically persist the AssistantTurn, IntentResult, an optional
     * Seed / Draft diff, an optional Checkpoint, the terminal Run status and
     * the terminal SSE event.
     */
    public synchronized void completeRun(String runId, String sessionId,
                                         String status, String assistantContent,
                                         IntentResult intent, StrategySeed seed,
                                         RunCheckpoint checkpoint, DraftPatch patch,
                                         int stepsUsed, int toolCallsUsed,
                                         int modelCallsUsed, int outputChars,
                                         String error, boolean modelUnavailable,
                                         String nextQuestion) {
        ensureInitialized();
        String now = Instant.now().toString();
        AgentRun run = findRun(runId);
        if (run == null) {
            throw new IllegalStateException("unknown run " + runId);
        }
        String intentLabel = intent == null ? run.intent() : intent.primaryIntent();
        // Persist the IntentResult.
        if (intent != null) {
            data.intentResults.put(runId, intent);
        }
        // Persist a detected Seed. If the harness did not set a source turn,
        // attribute it to the most recent USER turn of the session so the seed
        // can be resolved back to its session for Draft creation.
        StrategySeed turnSeed = null;
        if (seed != null) {
            String sourceTurnId = seed.sourceTurnId();
            if (sourceTurnId == null) {
                for (int i = data.turns.size() - 1; i >= 0; i--) {
                    ConversationTurn t = data.turns.get(i);
                    if (sessionId.equals(t.sessionId())
                            && ConversationTurn.ROLE_USER.equals(t.role())) {
                        sourceTurnId = t.id();
                        break;
                    }
                }
            }
            StrategySeed stamped = new StrategySeed(seed.id(), seed.summary(),
                    seed.instruments(), seed.entryHint(), seed.exitHint(),
                    seed.riskHint(), seed.intent(), seed.status(),
                    sourceTurnId, seed.createdAt(), seed.decidedAt());
            recordSeed(stamped);
            turnSeed = stamped;
        }
        // Persist an optional Checkpoint, and emit checkpoint.saved when one is
        // saved (so the SSE client observes the durable checkpoint).
        if (checkpoint != null) {
            data.checkpoints.put(checkpoint.id(), checkpoint);
            appendEventInternal(sessionId, runId, "checkpoint.saved", Map.of(
                    "checkpointId", checkpoint.id(),
                    "reason", checkpoint.reason() == null ? "" : checkpoint.reason(),
                    "runId", runId));
        }
        // The pending question (at most ONE per round). The field is derived
        // from the nextQuestion's owning field when available; the Service
        // computes the actual Draft field patch separately.
        TurnQuestion question = nextQuestion == null ? null
                : new TurnQuestion(null, nextQuestion);
        // Update the Run to terminal.
        AgentRun terminal = new AgentRun(run.id(), run.sessionId(), run.workspaceId(),
                intentLabel, status, run.budget(), stepsUsed, toolCallsUsed,
                outputChars, checkpoint == null ? run.checkpointId() : checkpoint.id(),
                modelUnavailable, error, run.createdAt(), now);
        replaceRun(terminal);
        // 第二轮 Issue #2（中文要点）：把【聊天驱动的 Draft patch】（如「止损 1.5%」）
        // 真正应用到本会话的活动 CO_CREATING Draft 上。patch 由 harness 确定性计算，
        // 这里负责落库：① TaskCenterStore.applyPatch 重算完整度/缺失字段；② 把 FieldEvidence
        // 归属到最近一次 USER turn（纠正消息本身）；③ 乐观锁版本 +1 并发 draft.updated 事件。
        // 关键不变量：【Store 写失败前不得告诉用户"已更新"】——applyPatch 抛异常时直接
        // 向上传播，不会进入下面的 toDraftView。旧 bug 是 completeRun 接收 patch 却【从不应用】。
        if (patch != null) {
            StrategySpecDraft active = data.drafts.values().stream()
                    .filter(d -> sessionId.equals(d.sessionId())
                            && StrategySpecDraft.STATUS_CO_CREATING.equals(d.status()))
                    .reduce((first, second) -> second) // most recent
                    .orElse(null);
            if (active != null) {
                String srcTurn = data.turns.stream()
                        .filter(t -> sessionId.equals(t.sessionId())
                                && ConversationTurn.ROLE_USER.equals(t.role()))
                        .reduce((first, second) -> second)
                        .map(ConversationTurn::id).orElse(null);
                StrategySpecDraft updated = com.supertrader.demo.taskcenter.TaskCenterStore
                        .applyPatch(active, patch, srcTurn);
                data.drafts.put(active.id(), updated);
                data.draftVersions.merge(active.id(), 1, Integer::sum);
                appendEventInternal(sessionId, null, "draft.updated", Map.of(
                        "draftId", active.id(),
                        "version", data.draftVersions.get(active.id()),
                        "completeness", updated.completeness()));
            }
        }
        // 第三轮 Issue C（中文要点）：只有【有真实内容】时才持久化 AssistantTurn。
        // STOPPED 的 run 传入的是空 assistantContent（见 AgentDemoRunCoordinator.stopRun），
        // 因此【不会创建任何 assistant 成功回合】——模型/工具的中间结果被彻底丢弃，
        // 不会出现"研究检索被拒绝…"这类兜底回复。空内容若写入会误导用户以为成功。
        boolean hasAssistantBody = assistantContent != null && !assistantContent.isBlank();
        if (hasAssistantBody) {
            String assistantTurnId = "turn-" + UUID.randomUUID();
            ConversationTurn assistant = new ConversationTurn(assistantTurnId, sessionId,
                    workspaceId, ConversationTurn.ROLE_ASSISTANT, assistantContent,
                    intentLabel, modelUnavailable, turnSeed, question, now);
            data.turns.add(assistant);
        }
        // Terminal event.
        String terminalType = switch (status) {
            case AgentRun.STATUS_COMPLETED -> "run.completed";
            case AgentRun.STATUS_CHECKPOINTED -> "run.checkpointed";
            case AgentRun.STATUS_FAILED -> "run.failed";
            case AgentRun.STATUS_STOPPED -> "run.stopped";
            default -> "run.completed";
        };
        // Terminal event — include the stable error code when present so clients
        // (Inspector / SSE consumers) can render CAPABILITY_NOT_REGISTERED etc.
        java.util.Map<String, Object> terminalPayload = error == null || error.isBlank()
                ? Map.of("status", status)
                : Map.of("status", status, "errorCode", error);
        appendEventInternal(sessionId, runId, terminalType, terminalPayload);
        flushOrThrow();
    }

    // ------------------------------------------------------------------ //
    // Seed decision / Draft (Task 8 primitives; pure store here)
    // ------------------------------------------------------------------ //

    /** Persist a detected Seed (DETECTED). */
    public synchronized void recordSeed(StrategySeed seed) {
        ensureInitialized();
        // Replace any existing seed with the same id; otherwise append.
        data.seeds.removeIf(s -> s.id().equals(seed.id()));
        data.seeds.add(seed);
        flushOrThrow();
    }

    /**
     * Apply a user's decision on a Seed. {@link StrategySeed#STATUS_CONFIRMED}
     * creates a fresh CO_CREATING Draft; {@link StrategySeed#STATUS_DISCUSSED}
     * keeps it as discussion only (no Draft).
     */
    public synchronized DraftOutcome confirmSeed(String seedId, String decision,
                                                 String requestedDraftId) {
        ensureInitialized();
        StrategySeed seed = data.seeds.stream()
                .filter(s -> s.id().equals(seedId)).findFirst().orElse(null);
        if (seed == null) {
            return DraftOutcome.notFound();
        }
        String now = Instant.now().toString();
        StrategySeed updated = new StrategySeed(seed.id(), seed.summary(),
                seed.instruments(), seed.entryHint(), seed.exitHint(),
                seed.riskHint(), seed.intent(), decision, seed.sourceTurnId(),
                seed.createdAt(), now);
        recordSeed(updated);
        if (!StrategySeed.STATUS_CONFIRMED.equals(decision)) {
            return DraftOutcome.noDraft();
        }
        // Resolve the session owning this seed (via the source turn).
        String sessionId = data.turns.stream()
                .filter(t -> t.id().equals(seed.sourceTurnId()))
                .map(ConversationTurn::sessionId).findFirst()
                .orElse(workspaceId);
        String draftId = requestedDraftId == null || requestedDraftId.isBlank()
                ? "draft-" + UUID.randomUUID() : requestedDraftId;
        List<String> instruments = seed.instruments() == null
                ? List.of() : List.copyOf(seed.instruments());

        // P0-3（中文要点）：把 Seed 已识别的标量字段（fastWindow / slowWindow /
        // stopLossPct / positionSize）【带进 Draft】。这些值不在 StrategySeed 上（它只有
        // 字符串 hint），而在【产生该 Seed 的那次 run 的持久化 IntentResult.extractedFields】
        // 里——因此这里通过 latestIntentForSession(sessionId) 取回。否则 Draft 会被硬编码成
        // completeness=0、所有字段缺失（旧 bug）。取回后用 SpecParameters.smaV2(...) 装进
        // 结构化参数，并调用 recomputeDraft 重算完整度/缺失字段。
        IntentResult seedIntent = latestIntentForSession(sessionId);
        java.util.Map<String, Object> extracted = seedIntent == null
                ? java.util.Map.of() : seedIntent.extractedFields();
        Integer fastWindow = asInteger(extracted.get("fastWindow"));
        Integer slowWindow = asInteger(extracted.get("slowWindow"));
        Double positionSize = asDouble(extracted.get("positionSize"));
        Double stopLossPct = asDouble(extracted.get("stopLossPct"));
        SpecParameters parameters = SpecParameters.smaV2(
                fastWindow, slowWindow, positionSize, stopLossPct, null, null);

        // Seed evidence: one FieldEvidence per detected scalar / hint field,
        // referencing the seed's source user turn.
        java.util.List<FieldEvidence> seedEvidence = new ArrayList<>();
        String srcTurn = seed.sourceTurnId();
        for (String f : instruments.isEmpty() ? java.util.List.of("instruments")
                : java.util.List.of("instruments")) {
            seedEvidence.add(new FieldEvidence(f, srcTurn,
                    instruments.isEmpty() ? "" : String.join(",", instruments), now));
        }
        if (fastWindow != null) seedEvidence.add(new FieldEvidence(
                "fastWindow", srcTurn, String.valueOf(fastWindow), now));
        if (slowWindow != null) seedEvidence.add(new FieldEvidence(
                "slowWindow", srcTurn, String.valueOf(slowWindow), now));
        if (stopLossPct != null) seedEvidence.add(new FieldEvidence(
                "stopLossPct", srcTurn, String.valueOf(stopLossPct), now));
        if (positionSize != null) seedEvidence.add(new FieldEvidence(
                "positionSize", srcTurn, String.valueOf(positionSize), now));

        StrategySpecDraft draft = new StrategySpecDraft(draftId, workspaceId,
                sessionId, null, null, seed.id(), null,
                StrategySpecDraft.TEMPLATE_SMA_CROSS, instruments,
                null, parameters, seed.entryHint(), seed.exitHint(), seed.riskHint(),
                null, seedEvidence, 0, StrategySpecDraft.REQUIRED_FIELDS,
                StrategySpecDraft.STATUS_CO_CREATING, ownerId, now, now);
        // Recompute completeness / missingFields from the populated fields
        // (shared domain logic — never hardcode completeness=0).
        draft = recomputeDraft(draft);
        data.drafts.put(draftId, draft);
        // Draft v0 + emit a draft.updated event so the SSE client refreshes.
        data.draftVersions.put(draftId, 0);
        appendEventInternal(sessionId, null, "draft.updated", Map.of(
                "draftId", draftId, "version", 0,
                "completeness", draft.completeness()));
        flushOrThrow();
        return DraftOutcome.of(draft);
    }

    /** Resolve the latest persisted IntentResult for a session (by run order). */
    private IntentResult latestIntentForSession(String sessionId) {
        IntentResult found = null;
        for (int i = data.runs.size() - 1; i >= 0; i--) {
            AgentRun r = data.runs.get(i);
            if (sessionId.equals(r.sessionId())) {
                IntentResult ir = data.intentResults.get(r.id());
                if (ir != null) {
                    found = ir;
                    break;
                }
            }
        }
        return found;
    }

    /** Recompute completeness / missingFields using the shared domain helpers. */
    private static StrategySpecDraft recomputeDraft(StrategySpecDraft d) {
        return new StrategySpecDraft(d.id(), d.workspaceId(), d.sessionId(),
                d.strategyId(), d.baseVersionNumber(), d.seedId(), d.name(),
                d.templateType(), d.instruments(), d.timeframe(), d.parameters(),
                d.entryCondition(), d.exitCondition(), d.riskLimits(),
                d.backtestAssumptions(), d.evidence(),
                SpecFieldHelpers.completeness(d),
                SpecFieldHelpers.missingFields(d), d.status(),
                d.createdByMemberId(), d.createdAt(), d.updatedAt());
    }

    private static Integer asInteger(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.valueOf(s.trim()); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.valueOf(s.trim()); } catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    /**
     * Save a fully-formed Draft (the caller — AgentDemoService in Task 8 —
     * computes the field patch deterministically, reusing the existing
     * validated StrategySpecValidator / SpecFieldHelpers). The Store stays a
     * dumb persistence Adapter.
     */
    public synchronized void saveDraft(StrategySpecDraft draft) {
        ensureInitialized();
        data.drafts.put(draft.id(), draft);
        flushOrThrow();
    }

    /** Persist a session (create / no-op if it already exists). */
    public synchronized void saveSession(ConversationSession session) {
        ensureInitialized();
        data.sessions.put(session.id(), session);
        flushOrThrow();
    }

    /**
     * P1-3: durably persist the traced AgentSteps of a Run so a refresh / the
     * Session Detail can restore stepsByRun. Steps are written atomically with
     * a single flush; the SSE step.started/step.completed events are emitted
     * separately by the Coordinator's observer path.
     */
    public synchronized void persistSteps(String runId, String sessionId,
                                          java.util.List<AgentStep> steps) {
        ensureInitialized();
        if (steps == null || steps.isEmpty()) return;
        // Drop any previously-persisted steps for this run (idempotent on retry).
        data.steps.removeIf(s -> runId.equals(s.runId()));
        // Re-stamp each step with the AUTHORITATIVE runId / sessionId (the
        // harness builds steps with an internal runId; the Coordinator owns the
        // real one).
        for (AgentStep s : steps) {
            AgentStep stamped = new AgentStep(s.id(), runId, sessionId,
                    s.workspaceId(), s.seq(), s.kind(), s.capability(),
                    s.input(), s.output(), s.truncated(), s.durationMs(),
                    s.createdAt());
            data.steps.add(stamped);
        }
        flushOrThrow();
    }

    /** Look up a Draft by id (may be null). */
    public synchronized StrategySpecDraft draft(String draftId) {
        ensureInitialized();
        return data.drafts.get(draftId);
    }

    /** The Demo-managed optimistic-lock version of a Draft (starts at 0). */
    public synchronized int draftVersion(String draftId) {
        ensureInitialized();
        return data.draftVersions.getOrDefault(draftId, 0);
    }

    /**
     * Apply a validated field patch to a Draft (the Service computes the
     * deterministic patch), bumping the Demo-managed optimistic-lock version.
     * Returns the new Draft.
     */
    public synchronized StrategySpecDraft applyDraftPatch(String draftId,
                                                          com.supertrader.demo.taskcenter.TaskCenterDtos.DraftPatch patch,
                                                          String sourceTurnId) {
        ensureInitialized();
        StrategySpecDraft d = data.drafts.get(draftId);
        if (d == null) return null;
        StrategySpecDraft updated = com.supertrader.demo.taskcenter.TaskCenterStore
                .applyPatch(d, patch, sourceTurnId);
        data.drafts.put(draftId, updated);
        data.draftVersions.merge(draftId, 1, Integer::sum);
        // Emit a draft.updated event so the SSE client refreshes the card.
        appendEventInternal(d.sessionId(), null, "draft.updated",
                java.util.Map.of("draftId", draftId,
                        "version", data.draftVersions.get(draftId)));
        flushOrThrow();
        return updated;
    }

    // ------------------------------------------------------------------ //
    // Read accessors
    // ------------------------------------------------------------------ //

    public synchronized Snapshot snapshot() {
        ensureInitialized();
        return new Snapshot(
                new LinkedHashMap<>(data.sessions),
                new ArrayList<>(data.turns),
                new ArrayList<>(data.runs),
                new ArrayList<>(data.steps),
                new LinkedHashMap<>(data.checkpoints),
                new LinkedHashMap<>(data.intentResults),
                new ArrayList<>(data.seeds),
                new LinkedHashMap<>(data.drafts),
                new ArrayList<>(data.events),
                new LinkedHashMap<>(data.idempotency));
    }

    public synchronized List<AgentDemoDtos.DemoEventEnvelope> eventsFor(String sessionId,
                                                                        long after) {
        ensureInitialized();
        List<AgentDemoDtos.DemoEventEnvelope> out = new ArrayList<>();
        for (AgentDemoDtos.DemoEventEnvelope e : data.events) {
            if (sessionId.equals(e.sessionId()) && e.seq() > after) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized long lastEventSeq(String sessionId) {
        ensureInitialized();
        long max = 0;
        for (AgentDemoDtos.DemoEventEnvelope e : data.events) {
            if (sessionId.equals(e.sessionId()) && e.seq() > max) max = e.seq();
        }
        return max;
    }

    // ------------------------------------------------------------------ //
    // Internals: load / flush / quarantine
    // ------------------------------------------------------------------ //

    private void ensureInitialized() {
        if (data == null) {
            loadOrReset();
        }
    }

    private void loadOrReset() {
        SnapshotData fresh = SnapshotData.empty();
        if (!Files.exists(file)) {
            this.data = fresh;
            return;
        }
        try {
            String raw = Files.readString(file);
            if (raw.isBlank()) {
                this.data = fresh;
                return;
            }
            PersistedSnapshot p = MAPPER.readValue(raw, PersistedSnapshot.class);
            if (!PROTOCOL.equals(p.protocol)) {
                log.warn("agent-demo store protocol mismatch ({}), starting empty", p.protocol);
                this.data = fresh;
                return;
            }
            this.data = p.toData();
            // Re-hydrate the per-session event seq counter.
            long maxSeq = 0;
            for (AgentDemoDtos.DemoEventEnvelope e : this.data.events) {
                if (e.seq() > maxSeq) maxSeq = e.seq();
            }
            globalEventSeq.set(maxSeq);
        } catch (Exception e) {
            log.warn("agent-demo store corrupt, quarantining and starting empty: {}",
                    e.getClass().getSimpleName());
            quarantine();
            this.data = fresh;
        }
    }

    private void quarantine() {
        try {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-"
                    + System.currentTimeMillis());
            Files.move(file, aside);
        } catch (IOException ignore) {
            // Best-effort quarantine; never block startup.
        }
    }

    /** Atomic flush: temp file in the SAME directory + exclusive lock + force + move. */
    private void flush() {
        if (data == null) return;
        flushOrThrow();
    }

    private void flushOrThrow() {
        if (data == null) return;
        PersistedSnapshot p = PersistedSnapshot.of(data);
        Path parent = file.toAbsolutePath().getParent();
        // P0-2（中文要点）：让持久化写入【中断安全】。Stop 会用 Future.cancel(true)
        // 中断 worker，若中断恰好落在持有 FileLock 期间，会抛 FileLockInterruptionException
        // → 原子写失败 → failRun() 用 FAILED 覆盖本应正确的 STOPPED 终态（旧 bug）。
        // 做法：写盘期间先 Thread.interrupted() 清除中断标志，写完后恢复，让 worker 在
        // 下一个检查点再观察到取消。这样 STOPPED 的原子写永不被中断破坏。
        boolean interruptedBefore = Thread.interrupted();
        try {
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent == null ? file.getParent()
                    : parent, "agent-demo-", ".tmp");
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 FileLock lock = ch.lock()) {
                byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(p);
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            // Atomic move (overwrite target if it exists).
            try {
                Files.move(tmp, file,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new AgentDemoStoreException("agent-demo store write failed (fail-closed)", e);
        } finally {
            if (interruptedBefore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private long nextEventSeq(String sessionId) {
        // Monotonic per store (and therefore per session, since a session is
        // served by one store). Strictly increasing across all events.
        return globalEventSeq.incrementAndGet();
    }

    private void updateRunUsage(String runId, String status, int stepsUsed,
                                int toolCallsUsed, int modelCallsUsed, int outputChars,
                                String checkpointId, String error, String finishedAt) {
        AgentRun r = findRun(runId);
        if (r == null) return;
        AgentRun updated = new AgentRun(r.id(), r.sessionId(), r.workspaceId(),
                r.intent(), status == null ? r.status() : status, r.budget(),
                stepsUsed, toolCallsUsed, outputChars,
                checkpointId == null ? r.checkpointId() : checkpointId,
                r.modelUnavailable(), error == null ? r.error() : error,
                r.createdAt(), finishedAt == null ? r.finishedAt() : finishedAt);
        replaceRun(updated);
    }

    private AgentRun findRun(String runId) {
        return data.runs.stream().filter(r -> r.id().equals(runId))
                .findFirst().orElse(null);
    }

    private void replaceRun(AgentRun updated) {
        for (int i = 0; i < data.runs.size(); i++) {
            if (data.runs.get(i).id().equals(updated.id())) {
                data.runs.set(i, updated);
                return;
            }
        }
        data.runs.add(updated);
    }

    private static String defaultTitle(String content) {
        if (content == null || content.isBlank()) return "新会话";
        String t = content.trim();
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }

    // ------------------------------------------------------------------ //
    // Outcome / snapshot types
    // ------------------------------------------------------------------ //

    /** The result of {@link #acceptTurn}. */
    public static final class AcceptOutcome {
        public static final String OK = "OK";
        public static final String REPLAY = "REPLAY";
        public static final String CONFLICT = "CONFLICT";

        private final String status;
        private final String turnId;
        private final String runId;
        private final long eventSeq;
        private final AgentRun run;
        private final String conflictCode;

        private AcceptOutcome(String status, String turnId, String runId, long eventSeq,
                              AgentRun run, String conflictCode) {
            this.status = status;
            this.turnId = turnId;
            this.runId = runId;
            this.eventSeq = eventSeq;
            this.run = run;
            this.conflictCode = conflictCode;
        }

        static AcceptOutcome ok(String turnId, String runId, long eventSeq, AgentRun run) {
            return new AcceptOutcome(OK, turnId, runId, eventSeq, run, null);
        }

        static AcceptOutcome replay(String turnId, String runId, long eventSeq) {
            return new AcceptOutcome(REPLAY, turnId, runId, eventSeq, null, null);
        }

        static AcceptOutcome conflict() {
            return new AcceptOutcome(CONFLICT, null, null, 0L, null, "IDEMPOTENCY_CONFLICT");
        }

        public String status() { return status; }
        public String turnId() { return turnId; }
        public String runId() { return runId; }
        public AgentRun run() { return run; }
        public long eventSeq() { return eventSeq; }
        public boolean replayed() { return REPLAY.equals(status); }
        public String conflictCode() { return conflictCode; }
    }

    /** The result of {@link #confirmSeed}. */
    public static final class DraftOutcome {
        private final StrategySpecDraft draft;
        private final boolean found;
        private DraftOutcome(StrategySpecDraft draft, boolean found) {
            this.draft = draft; this.found = found;
        }
        static DraftOutcome of(StrategySpecDraft d) { return new DraftOutcome(d, true); }
        static DraftOutcome noDraft() { return new DraftOutcome(null, true); }
        static DraftOutcome notFound() { return new DraftOutcome(null, false); }
        public StrategySpecDraft draft() { return draft; }
        public boolean found() { return found; }
    }

    /** An immutable view of the persisted snapshot. */
    public static final class Snapshot {
        private final Map<String, ConversationSession> sessions;
        private final List<ConversationTurn> turns;
        private final List<AgentRun> runs;
        private final List<AgentStep> steps;
        private final Map<String, RunCheckpoint> checkpoints;
        private final Map<String, IntentResult> intentResults;
        private final List<StrategySeed> seeds;
        private final Map<String, StrategySpecDraft> drafts;
        private final List<AgentDemoDtos.DemoEventEnvelope> events;
        private final Map<String, IdempotencyRecord> idempotency;

        Snapshot(Map<String, ConversationSession> sessions,
                 List<ConversationTurn> turns, List<AgentRun> runs,
                 List<AgentStep> steps, Map<String, RunCheckpoint> checkpoints,
                 Map<String, IntentResult> intentResults, List<StrategySeed> seeds,
                 Map<String, StrategySpecDraft> drafts,
                 List<AgentDemoDtos.DemoEventEnvelope> events,
                 Map<String, IdempotencyRecord> idempotency) {
            this.sessions = Collections.unmodifiableMap(sessions);
            this.turns = Collections.unmodifiableList(turns);
            this.runs = Collections.unmodifiableList(runs);
            this.steps = Collections.unmodifiableList(steps);
            this.checkpoints = Collections.unmodifiableMap(checkpoints);
            this.intentResults = Collections.unmodifiableMap(intentResults);
            this.seeds = Collections.unmodifiableList(seeds);
            this.drafts = Collections.unmodifiableMap(drafts);
            this.events = Collections.unmodifiableList(events);
            this.idempotency = Collections.unmodifiableMap(idempotency);
        }
        public Map<String, ConversationSession> sessions() { return sessions; }
        public List<ConversationTurn> turns() { return turns; }
        public List<AgentRun> runs() { return runs; }
        public List<AgentStep> steps() { return steps; }
        public Map<String, RunCheckpoint> checkpoints() { return checkpoints; }
        public Map<String, IntentResult> intentResults() { return intentResults; }
        public List<StrategySeed> seeds() { return seeds; }
        public Map<String, StrategySpecDraft> drafts() { return drafts; }
        public List<AgentDemoDtos.DemoEventEnvelope> events() { return events; }
        public Map<String, IdempotencyRecord> idempotency() { return idempotency; }
    }

    /** The mutable in-memory snapshot data. */
    private static final class SnapshotData {
        Map<String, ConversationSession> sessions = new LinkedHashMap<>();
        List<ConversationTurn> turns = new ArrayList<>();
        List<AgentRun> runs = new ArrayList<>();
        List<AgentStep> steps = new ArrayList<>();
        Map<String, RunCheckpoint> checkpoints = new LinkedHashMap<>();
        Map<String, IntentResult> intentResults = new LinkedHashMap<>();
        List<StrategySeed> seeds = new ArrayList<>();
        Map<String, StrategySpecDraft> drafts = new LinkedHashMap<>();
        Map<String, Integer> draftVersions = new LinkedHashMap<>();
        List<AgentDemoDtos.DemoEventEnvelope> events = new ArrayList<>();
        Map<String, IdempotencyRecord> idempotency = new LinkedHashMap<>();

        static SnapshotData empty() { return new SnapshotData(); }
    }

    /** On-disk snapshot (the only thing ever written to the JSON file). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static final class PersistedSnapshot {
        @JsonProperty("protocol") String protocol;
        @JsonProperty("sessions") List<ConversationSession> sessions;
        @JsonProperty("turns") List<ConversationTurn> turns;
        @JsonProperty("runs") List<AgentRun> runs;
        @JsonProperty("steps") List<AgentStep> steps;
        @JsonProperty("checkpoints") List<RunCheckpoint> checkpoints;
        @JsonProperty("intentResults") List<IntentResultEntry> intentResults;
        @JsonProperty("seeds") List<StrategySeed> seeds;
        @JsonProperty("drafts") List<StrategySpecDraft> drafts;
        @JsonProperty("draftVersions") List<DraftVersionEntry> draftVersions;
        @JsonProperty("events") List<AgentDemoDtos.DemoEventEnvelope> events;
        @JsonProperty("idempotency") List<IdempotencyRecord> idempotency;

        static PersistedSnapshot of(SnapshotData d) {
            PersistedSnapshot p = new PersistedSnapshot();
            p.protocol = PROTOCOL;
            p.sessions = new ArrayList<>(d.sessions.values());
            p.turns = d.turns;
            p.runs = d.runs;
            p.steps = d.steps;
            p.checkpoints = new ArrayList<>(d.checkpoints.values());
            p.intentResults = d.intentResults.entrySet().stream()
                    .map(e -> new IntentResultEntry(e.getKey(), e.getValue()))
                    .toList();
            p.seeds = d.seeds;
            p.drafts = new ArrayList<>(d.drafts.values());
            p.draftVersions = d.draftVersions.entrySet().stream()
                    .map(e -> new DraftVersionEntry(e.getKey(), e.getValue()))
                    .toList();
            p.events = d.events;
            p.idempotency = new ArrayList<>(d.idempotency.values());
            return p;
        }

        SnapshotData toData() {
            SnapshotData d = SnapshotData.empty();
            if (sessions != null) for (ConversationSession s : sessions) d.sessions.put(s.id(), s);
            if (turns != null) d.turns.addAll(turns);
            if (runs != null) d.runs.addAll(runs);
            if (steps != null) d.steps.addAll(steps);
            if (checkpoints != null) for (RunCheckpoint c : checkpoints) d.checkpoints.put(c.id(), c);
            if (intentResults != null)
                for (IntentResultEntry e : intentResults) d.intentResults.put(e.runId(), e.result());
            if (seeds != null) d.seeds.addAll(seeds);
            if (drafts != null) for (StrategySpecDraft dr : drafts) d.drafts.put(dr.id(), dr);
            if (draftVersions != null)
                for (DraftVersionEntry v : draftVersions) d.draftVersions.put(v.draftId(), v.version());
            if (events != null) d.events.addAll(events);
            if (idempotency != null)
                for (IdempotencyRecord r : idempotency) d.idempotency.put(r.key(), r);
            return d;
        }
    }

    private record DraftVersionEntry(@JsonProperty("draftId") String draftId,
                                     @JsonProperty("version") int version) {}

    private record IntentResultEntry(@JsonProperty("runId") String runId,
                                     @JsonProperty("result") IntentResult result) {}

    private record IdempotencyRecord(
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("route") String route,
            @JsonProperty("key") String key,
            @JsonProperty("bodyHash") String bodyHash,
            @JsonProperty("turnId") String turnId,
            @JsonProperty("runId") String runId,
            @JsonProperty("eventSeq") long eventSeq) {}

    /** Raised when a durable write fails (fail-closed). */
    public static final class AgentDemoStoreException extends RuntimeException {
        AgentDemoStoreException(String msg, Throwable cause) { super(msg, cause); }
    }
}
