package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supertrader.demo.strategy.StrategyStore;
import com.supertrader.demo.strategy.StrategyVersion;
import com.supertrader.demo.team.TeamMember;
import com.supertrader.demo.team.TeamStore;
import com.supertrader.demo.workspace.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The local, disk-persisted task-center store (Module 9): sessions, turns,
 * Agent runs/steps/checkpoints, strategy drafts, frozen spec snapshots,
 * validation reports, tasks, approvals, backtest runs and the append-only
 * audit trail.
 *
 * <p>Contract:
 * <ul>
 *   <li>Persists ONLY the whitelisted fields of the domain records — never
 *       credentials, tokens, private keys, accounts or any trading field; user
 *       message content is stored only after server-side validation (credential
 *       shapes are rejected, 400).</li>
 *   <li>Persists to a single JSON file whose default location is
 *       {@code ../.run/task-center.json} relative to the backend working dir
 *       (backend/), i.e. {@code rebuild/.run/task-center.json} — the ONLY
 *       allowed location, Git-ignored. The environment variable
 *       {@code SIMNOW_TASK_CENTER_FILE} overrides it (integration tests MUST
 *       point it at a validated mkdtemp directory). The freeze-transaction
 *       marker lives next to it ({@code <file>.freeze-txn}).</li>
 *   <li>SAFE RECOVERY: a missing / empty / corrupt file, or a file holding
 *       illegal entries, never fails startup or any request — the store
 *       repairs it deterministically and writes the clean snapshot back
 *       atomically. Frozen snapshots and completed backtest results are NEVER
 *       modified or dropped by the repair; audit events are append-only.</li>
 *   <li>Loading / repairing / starting / page opening NEVER trigger an Agent
 *       run, a backtest, a diagnosis, the probe or SimNow.</li>
 *   <li>Strict per-workspace isolation: every read/write is scoped to the
 *       CURRENT workspace (from {@link WorkspaceStore}); an object of another
 *       workspace is deliberately "not found" (404). Requests NEVER carry a
 *       workspaceId, object id, actor or timestamp — those are always derived
 *       from server state.</li>
 *   <li>ALL permission decisions are enforced HERE in the server (RBAC
 *       re-checked on every write). OWNER / ADMIN / TRADER may create sessions,
 *       edit drafts, request validation and submit approval; VIEWER is
 *       read-only; only OWNER / ADMIN may approve or reject; TRADER never
 *       approves. A self-approval is recorded with {@code selfApproval=true}
 *       and is never disguised as a two-person approval.</li>
 *   <li>The approve-and-freeze transaction spans TWO stores (strategies.json +
 *       task-center.json) which cannot be replaced in one atomic move; it uses
 *       a recoverable transaction marker and deterministic recovery on the
 *       next load (see {@link #approveAndFreeze} / {@link #recoverInterruptedFreeze()})
 *       so a crash can NEVER leave a half-frozen state.</li>
 *   <li>There is NO delete route anywhere; cancellation is a state transition
 *       with a full audit trail and is explicitly unrelated to trading order
 *       cancellation.</li>
 * </ul>
 *
 * <p>Concurrency: all mutating/reading entry points are {@code synchronized};
 * the file is read on every operation and written atomically (tmp + move).
 */
@Component
public class TaskCenterStore {

    private static final Logger log = LoggerFactory.getLogger(TaskCenterStore.class);

    static final String SCHEMA = "task-center.v1";
    static final String FREEZE_TXN_SCHEMA = "freeze-txn.v1";
    static final int MAX_CONTENT = 4000;
    static final int MAX_REASON = 500;
    static final int MAX_SEED_SUMMARY = 500;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private final Path file;
    private final Path markerFile;
    private final WorkspaceStore workspaceStore;
    private final TeamStore teamStore;
    private final StrategyStore strategyStore;
    private final StrategySpecValidator validator;
    private final DatasetCatalog catalog;
    private final BacktestRunnerService runner;

    /**
     * The executor actually used by {@link #startTask}. Production always uses
     * {@link #runner}; tests may install a latch-controlled fake via
     * {@link #setExecutorForTests(BacktestExecutor)} to make the RUNNING
     * cancellation and attempt-lifecycle races deterministic.
     */
    private volatile BacktestExecutor executor;

    /**
     * Process-local cooperative cancellation signals for in-flight backtest
     * attempts, keyed by {@code taskId + ":" + attempt}. cancelTask() sets the
     * flag while it atomically persists the CANCELLED state; the runner polls
     * the flag outside the store lock and the conditional merge in
     * finishStart() never overwrites a persisted cancellation. The map is
     * in-memory only — after a crash/restart, loadAndRepair() deterministically
     * repairs any RUNNING attempt to CANCELLED, so no stale signal survives.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>
            cancellationFlags = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * TEST-ONLY seam for the freeze-transaction exception-injection tests:
     * runs just before every task-center persist so a test can simulate a
     * crash between the strategies write and the task-center write. Defaults
     * to a no-op; never set by production code.
     */
    private volatile Runnable persistHook = () -> {};

    public TaskCenterStore(@Value("${app.taskcenter.file}") String taskCenterFile,
                           WorkspaceStore workspaceStore,
                           TeamStore teamStore,
                           StrategyStore strategyStore,
                           StrategySpecValidator validator,
                           DatasetCatalog catalog,
                           BacktestRunnerService runner) {
        this.file = Path.of(taskCenterFile);
        this.markerFile = Path.of(taskCenterFile + ".freeze-txn");
        this.workspaceStore = workspaceStore;
        this.teamStore = teamStore;
        this.strategyStore = strategyStore;
        this.validator = validator;
        this.catalog = catalog;
        this.runner = runner;
        this.executor = runner;
    }

    /** TEST-ONLY: install a latch-controlled executor for deterministic
     *  concurrency tests; {@code null} restores the production runner. */
    void setExecutorForTests(BacktestExecutor e) {
        this.executor = e == null ? this.runner : e;
    }

    /** TEST-ONLY: install a hook run before every task-center persist. */
    void setPersistHookForTests(Runnable hook) {
        this.persistHook = hook == null ? () -> {} : hook;
    }

    /** Visible for tests: the resolved store file path. */
    Path filePath() {
        return file;
    }

    /** Visible for tests: the freeze-transaction marker path. */
    Path markerPath() {
        return markerFile;
    }

    /** The id of the CURRENT workspace (always derived from server state). */
    public synchronized String currentWorkspaceId() {
        return workspaceStore.currentWorkspaceId();
    }

    // ================================================================== //
    // Read API (scoped to the CURRENT workspace)
    // ================================================================== //

    public synchronized List<ConversationSession> listSessions() {
        String wsId = currentWorkspaceId();
        return loadAndRepair().sessions().stream()
                .filter(s -> s.workspaceId().equals(wsId))
                .sorted(Comparator.comparing(ConversationSession::createdAt))
                .toList();
    }

    /** Session detail: session + turns + drafts + tasks (all current-workspace). */
    public synchronized SessionDetail sessionDetail(String sessionId) {
        Snapshot snap = loadAndRepair();
        ConversationSession session = sessionByIdOrThrow(snap, sessionId); // 404 + isolation
        String wsId = currentWorkspaceId();
        return new SessionDetail(session,
                snap.turns().stream()
                        .filter(t -> t.sessionId().equals(sessionId)
                                && t.workspaceId().equals(wsId))
                        .sorted(Comparator.comparing(ConversationTurn::createdAt))
                        .toList(),
                snap.drafts().stream()
                        .filter(d -> d.sessionId().equals(sessionId)
                                && d.workspaceId().equals(wsId))
                        .sorted(Comparator.comparing(StrategySpecDraft::createdAt))
                        .toList(),
                snap.tasks().stream()
                        .filter(t -> t.sessionId().equals(sessionId)
                                && t.workspaceId().equals(wsId))
                        .sorted(Comparator.comparing(AgentTask::createdAt))
                        .toList());
    }

    /** Draft detail: draft + its latest validation report + its frozen
     *  snapshot (nullable) + its approval records. */
    public synchronized DraftDetail draft(String draftId) {
        Snapshot snap = loadAndRepair();
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId); // 404 + isolation
        String wsId = currentWorkspaceId();
        ValidationReport latest = latestReport(snap, draftId);
        StrategySpecSnapshot snapshot = snap.snapshots().stream()
                .filter(s -> s.draftId().equals(draftId) && s.workspaceId().equals(wsId))
                .findFirst().orElse(null);
        List<ApprovalRecord> approvals = snap.approvals().stream()
                .filter(a -> (a.entityType().equals(ApprovalRecord.ENTITY_DRAFT)
                        || a.entityType().equals(ApprovalRecord.ENTITY_EVENT_EVIDENCE))
                        && a.entityId().equals(draftId) && a.workspaceId().equals(wsId))
                .sorted(Comparator.comparing(ApprovalRecord::decidedAt))
                .toList();
        return new DraftDetail(d, latest, snapshot, approvals);
    }

    /** Tasks of the current workspace, optionally filtered by status. */
    public synchronized List<AgentTask> tasks(String statusFilter) {
        String wsId = currentWorkspaceId();
        return loadAndRepair().tasks().stream()
                .filter(t -> t.workspaceId().equals(wsId))
                .filter(t -> statusFilter == null || statusFilter.isBlank()
                        || statusFilter.equals(t.status()))
                .sorted(Comparator.comparing(AgentTask::createdAt))
                .toList();
    }

    /** Task detail: task + approvals + its backtest runs (attempts ascending). */
    public synchronized TaskDetail task(String taskId) {
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, taskId); // 404 + isolation
        String wsId = currentWorkspaceId();
        List<ApprovalRecord> approvals = snap.approvals().stream()
                .filter(a -> a.entityType().equals(ApprovalRecord.ENTITY_TASK)
                        && a.entityId().equals(taskId) && a.workspaceId().equals(wsId))
                .sorted(Comparator.comparing(ApprovalRecord::decidedAt))
                .toList();
        List<BacktestRun> runs = snap.backtestRuns().stream()
                .filter(r -> r.taskId().equals(taskId) && r.workspaceId().equals(wsId))
                .sorted(Comparator.comparingInt(BacktestRun::attemptNumber))
                .toList();
        return new TaskDetail(t, approvals, runs);
    }

    /** Agent run detail: run + steps + checkpoint. */
    public synchronized AgentRunDetail agentRun(String runId) {
        Snapshot snap = loadAndRepair();
        AgentRun run = runByIdOrThrow(snap, runId); // 404 + isolation
        String wsId = currentWorkspaceId();
        List<AgentStep> steps = snap.agentSteps().stream()
                .filter(s -> s.runId().equals(runId) && s.workspaceId().equals(wsId))
                .sorted(Comparator.comparingInt(AgentStep::seq))
                .toList();
        RunCheckpoint checkpoint = snap.checkpoints().stream()
                .filter(c -> c.runId().equals(runId) && c.workspaceId().equals(wsId))
                .findFirst().orElse(null);
        return new AgentRunDetail(run, steps, checkpoint);
    }

    /** One backtest run (404 + isolation). */
    public synchronized BacktestRun backtestRun(String runId) {
        Snapshot snap = loadAndRepair();
        String wsId = currentWorkspaceId();
        return snap.backtestRuns().stream()
                .filter(r -> r.id().equals(runId) && r.workspaceId().equals(wsId))
                .findFirst()
                .orElseThrow(() -> TaskCenterApiException.runNotFound(
                        "当前工作空间不存在该回测运行：" + runId));
    }

    /** Task audit: approvals + all audit events touching the task. */
    public synchronized TaskAudit taskAudit(String taskId) {
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, taskId); // 404 + isolation
        String wsId = currentWorkspaceId();
        List<ApprovalRecord> approvals = snap.approvals().stream()
                .filter(a -> a.entityType().equals(ApprovalRecord.ENTITY_TASK)
                        && a.entityId().equals(taskId) && a.workspaceId().equals(wsId))
                .sorted(Comparator.comparing(ApprovalRecord::decidedAt))
                .toList();
        List<AuditEvent> events = snap.auditEvents().stream()
                .filter(a -> a.workspaceId().equals(wsId))
                .filter(a -> a.entityType().equals("TASK") && a.entityId().equals(taskId)
                        || a.entityType().equals("DRAFT") && a.entityId().equals(t.draftId()))
                .sorted(Comparator.comparing(AuditEvent::createdAt))
                .toList();
        return new TaskAudit(approvals, events);
    }

    // ------------------------------------------------------------------ //
    // Module 10 read-only queries (risk centre / controlled evolution).
    // These are PURE READ queries over the current workspace: they never
    // change any state, never trigger an Agent run / backtest / diagnosis /
    // probe / SimNow, and never modify the Module 9 history.
    // ------------------------------------------------------------------ //

    /** All tasks of the current workspace belonging to the given strategy
     *  (sorted by createdAt, then id). */
    public synchronized List<AgentTask> tasksForStrategy(String strategyId) {
        String wsId = currentWorkspaceId();
        return loadAndRepair().tasks().stream()
                .filter(t -> t.workspaceId().equals(wsId)
                        && strategyId.equals(t.strategyId()))
                .sorted(Comparator.comparing(AgentTask::createdAt)
                        .thenComparing(AgentTask::id))
                .toList();
    }

    /** All frozen spec snapshots of the current workspace belonging to the
     *  given strategy (sorted by frozenAt, then id). */
    public synchronized List<StrategySpecSnapshot> snapshotsForStrategy(String strategyId) {
        String wsId = currentWorkspaceId();
        return loadAndRepair().snapshots().stream()
                .filter(s -> s.workspaceId().equals(wsId)
                        && strategyId.equals(s.strategyId()))
                .sorted(Comparator.comparing(StrategySpecSnapshot::frozenAt)
                        .thenComparing(StrategySpecSnapshot::id))
                .toList();
    }

    /** All backtest runs of the current workspace belonging to the given
     *  strategy (via its tasks; sorted by startedAt, then id). */
    public synchronized List<BacktestRun> backtestRunsForStrategy(String strategyId) {
        String wsId = currentWorkspaceId();
        Snapshot snap = loadAndRepair();
        java.util.Set<String> taskIds = snap.tasks().stream()
                .filter(t -> t.workspaceId().equals(wsId)
                        && strategyId.equals(t.strategyId()))
                .map(AgentTask::id)
                .collect(java.util.stream.Collectors.toSet());
        return snap.backtestRuns().stream()
                .filter(r -> r.workspaceId().equals(wsId) && taskIds.contains(r.taskId()))
                .sorted(Comparator.comparing(BacktestRun::startedAt)
                        .thenComparing(BacktestRun::id))
                .toList();
    }

    /** The Draft with the given id of the current workspace, or {@code null}
     *  when unknown (a cross-workspace draft is deliberately "not found"). */
    public synchronized StrategySpecDraft draftQuiet(String draftId) {
        String wsId = currentWorkspaceId();
        return loadAndRepair().drafts().stream()
                .filter(d -> d.id().equals(draftId) && d.workspaceId().equals(wsId))
                .findFirst().orElse(null);
    }

    /** All Agent runs of the current workspace belonging to the given session
     *  (sorted by createdAt, then id). Steps / checkpoint are read via
     *  {@link #agentRun(String)}. */
    public synchronized List<AgentRun> agentRunsForSession(String sessionId) {
        String wsId = currentWorkspaceId();
        return loadAndRepair().agentRuns().stream()
                .filter(r -> r.workspaceId().equals(wsId) && r.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(AgentRun::createdAt)
                        .thenComparing(AgentRun::id))
                .toList();
    }

    /** All audit events of the current workspace with optional deterministic
     *  filters. {@code result} matches the trailing token of the action
     *  (e.g. result=SUCCEEDED matches TASK_RUN_SUCCEEDED); every filter is
     *  null/blank-tolerant. The timeline order is deterministic: createdAt,
     *  then id. */
    public synchronized List<AuditEvent> auditEvents(String fromIso, String toIso,
                                                     String entityType, String action,
                                                     String result) {
        String wsId = currentWorkspaceId();
        java.time.Instant from = parseInstantOrNull(fromIso);
        java.time.Instant to = parseInstantOrNull(toIso);
        String resultToken = result == null || result.isBlank() ? null
                : result.trim().toUpperCase(Locale.ROOT);
        return loadAndRepair().auditEvents().stream()
                .filter(a -> a.workspaceId().equals(wsId))
                .filter(a -> from == null || parseInstant(a.createdAt())
                        .compareTo(from) >= 0)
                .filter(a -> to == null || parseInstant(a.createdAt())
                        .compareTo(to) <= 0)
                .filter(a -> entityType == null || entityType.isBlank()
                        || entityType.equals(a.entityType()))
                .filter(a -> action == null || action.isBlank()
                        || action.equals(a.action()))
                .filter(a -> resultToken == null || a.action().equals(resultToken)
                        || a.action().endsWith("_" + resultToken))
                .sorted(Comparator.comparing(AuditEvent::createdAt)
                        .thenComparing(AuditEvent::id))
                .toList();
    }

    private static java.time.Instant parseInstant(String iso) {
        try {
            return java.time.Instant.parse(iso);
        } catch (Exception e) {
            return java.time.Instant.EPOCH;
        }
    }

    private static java.time.Instant parseInstantOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /** The latest validation report of a draft (or null). */
    public synchronized ValidationReport latestValidationReport(String draftId) {
        return latestReport(loadAndRepair(), draftId);
    }

    // ================================================================== //
    // Write API — sessions & turns
    // ================================================================== //

    /** Create a conversation session (OWNER / ADMIN / TRADER). */
    public synchronized ConversationSession createSession(String rawTitle) {
        ensureCanWrite();                                // 403
        String title = validateSessionTitle(rawTitle);   // 400 INVALID_NAME
        String now = Instant.now().toString();
        String memberId = currentMemberId();
        ConversationSession session = new ConversationSession(
                UUID.randomUUID().toString(), currentWorkspaceId(), title,
                ConversationSession.STATUS_ACTIVE, memberId, now, now);
        Snapshot snap = loadAndRepair();
        persist(snap.withSession(session).withAudit(audit(session.workspaceId(),
                "SESSION_CREATED", "SESSION", session.id(),
                "创建会话「" + title + "」")));
        return session;
    }

    /** Persist a USER turn (content already validated by the caller). */
    public synchronized ConversationTurn addUserTurn(String sessionId, String content,
                                                     String intent, StrategySeed seed) {
        ensureCanWrite();                                // 403
        Snapshot snap = loadAndRepair();
        ConversationSession session = sessionByIdOrThrow(snap, sessionId); // 404
        String now = Instant.now().toString();
        ConversationTurn turn = new ConversationTurn(
                UUID.randomUUID().toString(), session.id(), session.workspaceId(),
                ConversationTurn.ROLE_USER, content, intent, false, seed, null, now);
        persist(snap.withTurn(turn).withAudit(audit(session.workspaceId(),
                "TURN_USER_RECORDED", "SESSION", session.id(),
                "记录用户消息（intent=" + intent + "）")));
        return turn;
    }

    /** Persist an ASSISTANT turn (one question at most). */
    public synchronized ConversationTurn addAssistantTurn(String sessionId,
                                                          String content, TurnQuestion question,
                                                          boolean modelUnavailable, String intent) {
        Snapshot snap = loadAndRepair();
        ConversationSession session = sessionByIdOrThrow(snap, sessionId); // 404
        String now = Instant.now().toString();
        ConversationTurn turn = new ConversationTurn(
                UUID.randomUUID().toString(), session.id(), session.workspaceId(),
                ConversationTurn.ROLE_ASSISTANT, content, intent,
                modelUnavailable, null, question, now);
        persist(snap.withTurn(turn).withAudit(audit(session.workspaceId(),
                "TURN_ASSISTANT_RECORDED", "SESSION", session.id(),
                "记录助手回复（question=" + (question == null ? "none" : question.field()) + "）")));
        return turn;
    }

    /**
     * The user's explicit seed decision. CO_CREATE creates the Draft
     * (CO_CREATING) from the seed hints; DISCUSS keeps the seed as a
     * discussion only. A plain QA / unconfirmed seed NEVER creates a Draft.
     */
    public synchronized SeedDecisionResult decideSeed(String sessionId, String turnId,
                                                      String decision) {
        ensureCanWrite();                                // 403
        if (!"CO_CREATE".equals(decision) && !"DISCUSS".equals(decision)) {
            throw TaskCenterApiException.invalidDecision(
                    "decision 必须是 CO_CREATE 或 DISCUSS");
        }
        Snapshot snap = loadAndRepair();
        ConversationSession session = sessionByIdOrThrow(snap, sessionId); // 404
        ConversationTurn turn = snap.turns().stream()
                .filter(t -> t.id().equals(turnId) && t.sessionId().equals(sessionId)
                        && t.workspaceId().equals(session.workspaceId()))
                .findFirst()
                .orElseThrow(() -> TaskCenterApiException.turnNotFound(
                        "当前会话不存在该消息：" + turnId));
        if (turn.seed() == null) {
            throw TaskCenterApiException.illegalState("该消息没有可确认的策略候选");
        }
        if (!StrategySeed.STATUS_DETECTED.equals(turn.seed().status())) {
            throw TaskCenterApiException.illegalState("该策略候选已处理过");
        }
        String now = Instant.now().toString();
        StrategySeed seed = new StrategySeed(turn.seed().id(), turn.seed().summary(),
                turn.seed().instruments(), turn.seed().entryHint(), turn.seed().exitHint(),
                turn.seed().riskHint(), turn.seed().intent(),
                "CO_CREATE".equals(decision) ? StrategySeed.STATUS_CONFIRMED
                        : StrategySeed.STATUS_DISCUSSED,
                turn.seed().sourceTurnId(), turn.seed().createdAt(), now);
        ConversationTurn updatedTurn = new ConversationTurn(turn.id(), turn.sessionId(),
                turn.workspaceId(), turn.role(), turn.content(), turn.intent(),
                turn.modelUnavailable(), seed, turn.question(), turn.createdAt());

        StrategySpecDraft draft = null;
        if (StrategySeed.STATUS_CONFIRMED.equals(seed.status())) {
            // Only an EXPLICIT user choice enters co-creation. The parameters
            // object is never null (every value stays nullable while the
            // draft is being co-created).
            draft = new StrategySpecDraft(
                    UUID.randomUUID().toString(), session.workspaceId(), session.id(),
                    null, null, seed.id(), null, StrategySpecDraft.TEMPLATE_SMA_CROSS,
                    seed.instruments(), null,
                    SpecParameters.smaV2(null, null, null, null, null, null),
                    seed.entryHint(), seed.exitHint(), seed.riskHint(), null,
                    List.of(), 0, StrategySpecDraft.REQUIRED_FIELDS,
                    StrategySpecDraft.STATUS_CO_CREATING, currentMemberId(), now, now);
            draft = recompute(draft);
        }
        List<ConversationTurn> nextTurns = new ArrayList<>(snap.turns());
        nextTurns.replaceAll(t -> t.id().equals(turnId) ? updatedTurn : t);
        Snapshot next = new Snapshot(snap.sessions(), nextTurns, snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), snap.tasks(), snap.approvals(),
                snap.backtestRuns(), snap.auditEvents());
        if (draft != null) {
            next = next.withDraft(draft);
        }
        persist(next.withAudit(audit(session.workspaceId(),
                "SEED_DECIDED", "SESSION", session.id(),
                "策略候选已确认：" + seed.status()
                        + (draft != null ? "，进入策略共创" : "（仅作为讨论，不创建草案）"))));
        return new SeedDecisionResult(seed, draft);
    }

    // ================================================================== //
    // Write API — Drafts
    // ================================================================== //

    /** PATCH a draft (OWNER / ADMIN / TRADER). A REJECTED draft may be edited
     *  (it returns to CO_CREATING); PENDING_APPROVAL / APPROVED / FROZEN
     *  drafts are locked (a frozen draft can only be changed via a NEW draft). */
    public synchronized StrategySpecDraft updateDraft(String draftId,
                                                      TaskCenterDtos.DraftPatch patch,
                                                      String sourceTurnId) {
        ensureCanWrite();                                // 403
        if (patch == null) {
            throw TaskCenterApiException.invalidBody("请求体必须是合法的 JSON 对象");
        }
        if (allNull(patch)) {
            throw TaskCenterApiException.invalidBody("至少提供一个要修改的字段");
        }
        Snapshot snap = loadAndRepair();
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId); // 404 + isolation
        if (StrategySpecDraft.STATUS_FROZEN.equals(d.status())
                || StrategySpecDraft.STATUS_APPROVED.equals(d.status())) {
            throw TaskCenterApiException.illegalState(
                    "草案处于 " + d.status() + " 状态，不可修改；修改冻结内容必须创建新草案");
        }
        StrategySpecDraft updated = applyPatch(d, patch, sourceTurnId);
        rejectBannedContent(updated);                    // 400 INVALID_PARAMETER
        // A rejected or pending-approval draft that is edited returns to
        // co-creation (it must be re-validated and re-submitted).
        if (StrategySpecDraft.STATUS_REJECTED.equals(d.status())
                || StrategySpecDraft.STATUS_PENDING_APPROVAL.equals(d.status())) {
            updated = withStatus(updated, StrategySpecDraft.STATUS_CO_CREATING);
        }
        List<StrategySpecDraft> next = new ArrayList<>(snap.drafts());
        final StrategySpecDraft updatedFinal = updated;
        next.replaceAll(x -> x.id().equals(draftId) ? updatedFinal : x);
        persist(new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), next, snap.snapshots(),
                snap.validationReports(), snap.tasks(), snap.approvals(),
                snap.backtestRuns(), snap.auditEvents())
                .withAudit(audit(d.workspaceId(), "DRAFT_UPDATED", "DRAFT", draftId,
                        "更新草案字段（完整度 " + updated.completeness() + "%）")));
        return updated;
    }

    /** Independent OWNER/ADMIN confirmation of frozen EVENT source facts.
     * The confirmer, timestamp and confirmation id are always server-derived. */
    public synchronized StrategySpecDraft confirmEventEvidence(String draftId) {
        ensureCanApprove();
        Snapshot snap = loadAndRepair();
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId);
        if (StrategySpecDraft.STATUS_FROZEN.equals(d.status())
                || StrategySpecDraft.STATUS_APPROVED.equals(d.status())
                || StrategySpecDraft.STATUS_PENDING_APPROVAL.equals(d.status())) {
            throw TaskCenterApiException.illegalState(
                    "草案处于 " + d.status() + " 状态，不可确认事件证据");
        }
        if (!StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(d.templateType())
                || d.parameters() == null || d.parameters().eventSignal() == null) {
            throw TaskCenterApiException.illegalState(
                    "只有 EVENT_SIGNAL 草案才能确认事件证据");
        }
        SpecParameters.EventSignal e = d.parameters().eventSignal();
        if (e.confirmationId() != null || e.confirmedByMemberId() != null
                || e.confirmedAt() != null) {
            throw TaskCenterApiException.illegalState("事件证据已经确认；变更事实后需重新确认");
        }
        if (!"AVAILABLE".equals(e.sourceStatus())) {
            throw TaskCenterApiException.illegalState("事件来源必须 AVAILABLE 才能人工确认");
        }
        String confirmer = currentMemberId();
        String now = Instant.now().toString();
        try {
            if (e.availableAt() == null || Instant.parse(now).isBefore(Instant.parse(e.availableAt()))) {
                throw TaskCenterApiException.illegalState("事件尚未 available，不能人工确认");
            }
        } catch (TaskCenterApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw TaskCenterApiException.invalidParameter("availableAt 必须为 ISO-8601");
        }
        String confirmationId = UUID.randomUUID().toString();
        SpecParameters confirmedParameters = SpecParameters.event(
                new SpecParameters.EventSignal(e.eventId(), e.sourceId(), e.contentHash(),
                        e.originalPublishedAt(), e.fetchedAt(), e.availableAt(), e.validFrom(),
                        e.validUntil(), e.relatedInstrument(), e.direction(), confirmationId,
                        confirmer, now, e.sourceStatus(), e.stopLossPct(), e.positionSize(),
                        e.feeBps(), e.slippageBps()));
        List<FieldEvidence> evidence = new ArrayList<>(d.evidence() == null
                ? List.of() : d.evidence());
        evidence = withEvidence(evidence, "confirmationId", null, confirmationId, now);
        evidence = withEvidence(evidence, "confirmedByMemberId", null, confirmer, now);
        evidence = withEvidence(evidence, "confirmedAt", null, now, now);
        StrategySpecDraft updated = recompute(new StrategySpecDraft(d.id(), d.workspaceId(),
                d.sessionId(), d.strategyId(), d.baseVersionNumber(), d.seedId(), d.name(),
                d.templateType(), d.instruments(), d.timeframe(), confirmedParameters,
                d.entryCondition(), d.exitCondition(), d.riskLimits(),
                d.backtestAssumptions(), evidence, 0, List.of(),
                StrategySpecDraft.STATUS_CO_CREATING, d.createdByMemberId(), d.createdAt(), now));
        ApprovalRecord confirmation = new ApprovalRecord(confirmationId, d.workspaceId(),
                ApprovalRecord.ENTITY_EVENT_EVIDENCE, draftId,
                ApprovalRecord.DECISION_CONFIRMED, confirmer,
                eventConfirmationSummary(e), confirmer.equals(d.createdByMemberId()), now);
        List<StrategySpecDraft> drafts = new ArrayList<>(snap.drafts());
        drafts.replaceAll(x -> x.id().equals(draftId) ? updated : x);
        List<ApprovalRecord> approvals = new ArrayList<>(snap.approvals());
        approvals.add(confirmation);
        persist(new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), drafts, snap.snapshots(),
                snap.validationReports(), snap.tasks(), approvals, snap.backtestRuns(),
                snap.auditEvents()).withAudit(audit(d.workspaceId(),
                "EVENT_EVIDENCE_CONFIRMED", "EVENT_EVIDENCE", draftId,
                "OWNER/ADMIN 已独立确认 EVENT_SIGNAL 来源事实")));
        return updated;
    }

    /**
     * Run the DETERMINISTIC validator and persist its report; the draft
     * status becomes DRAFT_READY (valid + backtestable) or VALIDATION_FAILED.
     */
    public synchronized ValidationResponse validateDraft(String draftId) {
        ensureCanWrite();                                // 403
        Snapshot snap = loadAndRepair();
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId); // 404 + isolation
        ensureAuthoritativeEventConfirmation(d, snap.approvals());
        if (StrategySpecDraft.STATUS_FROZEN.equals(d.status())
                || StrategySpecDraft.STATUS_PENDING_APPROVAL.equals(d.status())
                || StrategySpecDraft.STATUS_APPROVED.equals(d.status())) {
            throw TaskCenterApiException.illegalState(
                    "草案处于 " + d.status() + " 状态，不可再次校验");
        }
        StrategySpecValidator.Validated v = validator.validate(d);
        String now = Instant.now().toString();
        ValidationReport report = new ValidationReport(
                UUID.randomUUID().toString(), draftId, d.workspaceId(),
                v.valid(), v.backtestable(), v.issues(), v.warnings(),
                now, validator.version());
        StrategySpecDraft updated = withStatus(d,
                v.valid() && v.backtestable() ? StrategySpecDraft.STATUS_DRAFT_READY
                        : StrategySpecDraft.STATUS_VALIDATION_FAILED);
        List<StrategySpecDraft> nextDrafts = new ArrayList<>(snap.drafts());
        nextDrafts.replaceAll(x -> x.id().equals(draftId) ? updated : x);
        List<ValidationReport> nextReports = new ArrayList<>(snap.validationReports());
        nextReports.add(report);
        persist(new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), nextDrafts, snap.snapshots(),
                nextReports, snap.tasks(), snap.approvals(), snap.backtestRuns(),
                snap.auditEvents())
                .withAudit(audit(d.workspaceId(), "DRAFT_VALIDATED", "DRAFT", draftId,
                        "确定性校验：valid=" + v.valid() + " backtestable=" + v.backtestable()
                                + "（" + v.issues().size() + " 个问题）")));
        return new ValidationResponse(updated, report);
    }

    /** Explicit user submission of the Draft for human approval. */
    public synchronized StrategySpecDraft submitApproval(String draftId, String reason) {
        ensureCanWrite();                                // 403
        Snapshot snap = loadAndRepair();
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId); // 404 + isolation
        if (!StrategySpecDraft.STATUS_DRAFT_READY.equals(d.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有通过确定性校验（DRAFT_READY）的草案才能提交人工审批");
        }
        ValidationReport latest = latestReport(snap, draftId);
        if (latest == null || !latest.valid() || !latest.backtestable()) {
            throw TaskCenterApiException.illegalState(
                    "草案未通过确定性校验，不能提交审批");
        }
        StrategySpecDraft updated = withStatus(d, StrategySpecDraft.STATUS_PENDING_APPROVAL);
        List<StrategySpecDraft> next = new ArrayList<>(snap.drafts());
        next.replaceAll(x -> x.id().equals(draftId) ? updated : x);
        persist(new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), next, snap.snapshots(),
                snap.validationReports(), snap.tasks(), snap.approvals(),
                snap.backtestRuns(), snap.auditEvents())
                .withAudit(audit(d.workspaceId(), "DRAFT_APPROVAL_SUBMITTED", "DRAFT",
                        draftId, "草案已提交人工审批" + (reason == null || reason.isBlank()
                                ? "" : "（理由：" + truncate(reason, 100) + "）"))));
        return updated;
    }

    /**
     * OWNER/ADMIN approval = approval record + the controlled approve-and-freeze
     * transaction: the readable Draft summary is mapped into a NEW immutable
     * Module-8 StrategyVersion AND a strictly-whitelisted immutable
     * StrategySpecSnapshot linked by strategyId + versionNumber. Both sides
     * must succeed; a crash can never leave a half-frozen state (recoverable
     * transaction marker + deterministic recovery on the next load).
     */
    public synchronized StrategySpecDraft approveAndFreeze(String draftId, String reason,
                                                           String decidedByMemberId) {
        ensureCanApprove();                              // 403 (OWNER/ADMIN only)
        Snapshot snap = loadAndRepair();
        if (Files.exists(markerFile)) {
            // Another freeze transaction is pending recovery — never overwrite
            // its marker (including unknown/unreadable marker bytes that must
            // remain available for manual recovery).
            throw TaskCenterApiException.illegalState(
                    "存在未完成的冻结事务（系统会在下次加载时自动完成该事务）；请稍后重试");
        }
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId); // 404 + isolation
        ensureAuthoritativeEventConfirmation(d, snap.approvals());
        if (!StrategySpecDraft.STATUS_PENDING_APPROVAL.equals(d.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有处于 PENDING_APPROVAL 的草案才能被批准冻结");
        }
        ValidationReport latest = latestReport(snap, draftId);
        if (latest == null || !latest.valid() || !latest.backtestable()) {
            throw TaskCenterApiException.illegalState(
                    "草案未通过确定性校验，不能批准冻结");
        }

        // ---- 1. pre-generate every id (deterministic replay) ----
        String now = Instant.now().toString();
        String approvalId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String snapshotId = UUID.randomUUID().toString();
        String auditApprovedId = UUID.randomUUID().toString();
        String auditFrozenId = UUID.randomUUID().toString();
        String strategyId = d.strategyId() == null ? UUID.randomUUID().toString()
                : d.strategyId();

        FreezeMarker marker = new FreezeMarker(FREEZE_TXN_SCHEMA, draftId, d.workspaceId(),
                d.sessionId(), d.seedId(), d.name(), d.templateType(), d.instruments(),
                d.timeframe(), d.parameters(), d.entryCondition(), d.exitCondition(),
                d.riskLimits(), d.backtestAssumptions(), strategyId,
                d.strategyId() == null, versionId, snapshotId, approvalId,
                auditApprovedId, auditFrozenId, d.createdByMemberId(),
                decidedByMemberId, reason == null ? "" : truncate(reason, MAX_REASON),
                now);
        writeMarker(marker);                             // crash-safe transaction marker

        // ---- 2. strategies side (Module-8 immutable version) ----
        int versionNumber;
        try {
            if (d.strategyId() == null) {
                // NEW strategy + version 1 with the pre-generated ids.
                strategyStore.createRecoveredStrategy(strategyId, versionId,
                        d.workspaceId(), d.name(), d.name(), d.instruments(),
                        d.timeframe(), d.entryCondition(), d.exitCondition(),
                        d.riskLimits(), d.createdByMemberId(), now, now);
                versionNumber = 1;
            } else {
                StrategyVersion v = strategyStore.appendVersionWithId(d.strategyId(),
                        versionId, new com.supertrader.demo.strategy.StrategyDtos.AppendVersionRequest(
                                d.name(), d.instruments(), d.timeframe(),
                                d.entryCondition(), d.exitCondition(), d.riskLimits()),
                        decidedByMemberId).version();
                versionNumber = v.versionNumber();
            }
        } catch (RuntimeException e) {
            // The marker stays on disk; the next load replays deterministically.
            log.warn("approveAndFreeze: strategies write failed (marker kept for recovery): {}",
                    e.getClass().getSimpleName());
            throw new TaskCenterApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    TaskCenterApiException.CODE_FREEZE_FAILED,
                    "冻结失败：无法写入策略版本（" + e.getMessage() + "）。系统会在下次加载时自动完成该事务。");
        }

        // ---- 3. task-center side (snapshot + FROZEN + approval + audit) ----
        StrategySpecSnapshot snapshot = new StrategySpecSnapshot(snapshotId, draftId,
                d.workspaceId(), strategyId, versionNumber, d.name(), d.templateType(),
                d.instruments(), d.timeframe(), d.parameters(), d.entryCondition(),
                d.exitCondition(), d.riskLimits(), d.backtestAssumptions(),
                contentHash(d), now, decidedByMemberId);
        ApprovalRecord approval = new ApprovalRecord(approvalId, d.workspaceId(),
                ApprovalRecord.ENTITY_DRAFT, draftId, ApprovalRecord.DECISION_APPROVED,
                decidedByMemberId, reason == null ? "" : truncate(reason, MAX_REASON),
                decidedByMemberId.equals(d.createdByMemberId()), now);
        AuditEvent auditApproved = new AuditEvent(auditApprovedId, d.workspaceId(),
                decidedByMemberId, "DRAFT_APPROVED", "DRAFT", draftId,
                "人工批准草案（selfApproval=" + approval.selfApproval() + "）", now);
        AuditEvent auditFrozen = new AuditEvent(auditFrozenId, d.workspaceId(),
                decidedByMemberId, "DRAFT_FROZEN", "DRAFT", draftId,
                "草案已冻结为不可变规格快照，并关联策略版本 strategyId=" + strategyId
                        + " version=" + versionNumber, now);
        StrategySpecDraft frozen = withStatus(d, StrategySpecDraft.STATUS_FROZEN);
        List<StrategySpecDraft> nextDrafts = new ArrayList<>(snap.drafts());
        nextDrafts.replaceAll(x -> x.id().equals(draftId) ? frozen : x);
        List<StrategySpecSnapshot> nextSnapshots = new ArrayList<>(snap.snapshots());
        nextSnapshots.add(snapshot);
        List<ApprovalRecord> nextApprovals = new ArrayList<>(snap.approvals());
        nextApprovals.add(approval);
        List<AuditEvent> nextAudits = new ArrayList<>(snap.auditEvents());
        nextAudits.add(auditApproved);
        nextAudits.add(auditFrozen);
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), nextDrafts, nextSnapshots,
                snap.validationReports(), snap.tasks(), nextApprovals,
                snap.backtestRuns(), nextAudits);
        try {
            persistWithHook(next);
        } catch (RuntimeException e) {
            // Marker stays; the next load replays the missing task-center side.
            log.warn("approveAndFreeze: task-center write failed (marker kept for recovery): {}",
                    e.getClass().getSimpleName());
            throw new TaskCenterApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    TaskCenterApiException.CODE_FREEZE_FAILED,
                    "冻结失败：无法写入任务中心（" + e.getMessage()
                            + "）。系统会在下次加载时自动完成该事务，不会留下半冻结状态。");
        }
        deleteMarker();
        return frozen;
    }

    /** OWNER/ADMIN rejection of a pending-approval Draft. */
    public synchronized StrategySpecDraft rejectDraft(String draftId, String reason) {
        ensureCanApprove();                              // 403 (OWNER/ADMIN only)
        Snapshot snap = loadAndRepair();
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId); // 404 + isolation
        if (!StrategySpecDraft.STATUS_PENDING_APPROVAL.equals(d.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有处于 PENDING_APPROVAL 的草案才能被拒绝");
        }
        String now = Instant.now().toString();
        StrategySpecDraft rejected = withStatus(d, StrategySpecDraft.STATUS_REJECTED);
        ApprovalRecord approval = new ApprovalRecord(UUID.randomUUID().toString(),
                d.workspaceId(), ApprovalRecord.ENTITY_DRAFT, draftId,
                ApprovalRecord.DECISION_REJECTED, currentMemberId(),
                reason == null ? "" : truncate(reason, MAX_REASON),
                currentMemberId().equals(d.createdByMemberId()), now);
        List<StrategySpecDraft> nextDrafts = new ArrayList<>(snap.drafts());
        nextDrafts.replaceAll(x -> x.id().equals(draftId) ? rejected : x);
        List<ApprovalRecord> nextApprovals = new ArrayList<>(snap.approvals());
        nextApprovals.add(approval);
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), nextDrafts, snap.snapshots(),
                snap.validationReports(), snap.tasks(), nextApprovals,
                snap.backtestRuns(), snap.auditEvents());
        persist(next.withAudit(audit(d.workspaceId(), "DRAFT_REJECTED", "DRAFT", draftId,
                "人工拒绝草案（selfApproval=" + approval.selfApproval() + "）")));
        return rejected;
    }

    // ================================================================== //
    // Write API — tasks & backtest runs
    // ================================================================== //

    /** Create a BACKTEST task from a FROZEN, validated Draft (OWNER / ADMIN /
     *  TRADER). The task starts PENDING_APPROVAL; it can never be started
     *  before approval. */
    public synchronized AgentTask createBacktestTask(String draftId, String datasetId) {
        ensureCanWrite();                                // 403
        Snapshot snap = loadAndRepair();
        StrategySpecDraft d = draftByIdOrThrow(snap, draftId); // 404 + isolation
        if (!StrategySpecDraft.STATUS_FROZEN.equals(d.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有已批准并冻结的草案才能创建回测任务");
        }
        ValidationReport latest = latestReport(snap, draftId);
        if (latest == null || !latest.backtestable()) {
            throw TaskCenterApiException.illegalState(
                    "草案未通过确定性校验（不可回测），不能创建回测任务");
        }
        StrategySpecSnapshot snapshot = snap.snapshots().stream()
                .filter(s -> s.draftId().equals(draftId) && s.workspaceId().equals(d.workspaceId()))
                .findFirst()
                .orElseThrow(() -> TaskCenterApiException.illegalState(
                        "草案已冻结但缺少不可变规格快照"));
        String tf = snapshot.timeframe();
        String instrument = snapshot.instruments().isEmpty() ? null
                : snapshot.instruments().get(0);
        List<DatasetCatalog.DatasetDescriptor> covering = catalog.coverage(instrument, tf);
        if (covering.isEmpty()) {
            throw TaskCenterApiException.dataUnavailable(
                    "没有覆盖 " + instrument + " " + tf
                            + " 的已登记本地数据集（内置验收样本或 DATA_UNAVAILABLE）");
        }
        String resolvedDatasetId = (datasetId == null || datasetId.isBlank())
                ? covering.get(0).datasetId() : datasetId;
        if (catalog.load(resolvedDatasetId) == null) {
            throw TaskCenterApiException.dataUnavailable(
                    "本地数据集不存在：" + resolvedDatasetId);
        }
        String now = Instant.now().toString();
        AgentTask task = new AgentTask(UUID.randomUUID().toString(), d.workspaceId(),
                d.sessionId(), draftId, snapshot.id(), snapshot.strategyId(),
                snapshot.versionNumber(), AgentTask.TYPE_BACKTEST, resolvedDatasetId,
                "回测 · " + snapshot.name(), AgentTask.STATUS_PENDING_APPROVAL,
                currentMemberId(), now, now, null, null, 0);
        List<AgentTask> nextTasks = new ArrayList<>(snap.tasks());
        nextTasks.add(task);
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), nextTasks, snap.approvals(),
                snap.backtestRuns(), snap.auditEvents());
        persist(next.withAudit(audit(d.workspaceId(), "TASK_CREATED", "TASK", task.id(),
                "创建回测任务（dataset=" + resolvedDatasetId + "，等待人工审批）")));
        return task;
    }

    /** OWNER/ADMIN approval of a pending-approval task. */
    public synchronized AgentTask approveTask(String taskId, String reason) {
        ensureCanApprove();                              // 403 (OWNER/ADMIN only)
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, taskId);     // 404 + isolation
        if (!AgentTask.STATUS_PENDING_APPROVAL.equals(t.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有处于 PENDING_APPROVAL 的任务才能被批准");
        }
        String now = Instant.now().toString();
        AgentTask approved = new AgentTask(t.id(), t.workspaceId(), t.sessionId(),
                t.draftId(), t.snapshotId(), t.strategyId(), t.versionNumber(),
                t.type(), t.datasetId(), t.name(), AgentTask.STATUS_APPROVED,
                t.requestedByMemberId(), t.createdAt(), now, t.cancelledAt(),
                t.cancelReason(), t.attemptCount());
        ApprovalRecord approval = new ApprovalRecord(UUID.randomUUID().toString(),
                t.workspaceId(), ApprovalRecord.ENTITY_TASK, taskId,
                ApprovalRecord.DECISION_APPROVED, currentMemberId(),
                reason == null ? "" : truncate(reason, MAX_REASON),
                currentMemberId().equals(t.requestedByMemberId()), now);
        List<AgentTask> nextTasks = new ArrayList<>(snap.tasks());
        nextTasks.replaceAll(x -> x.id().equals(taskId) ? approved : x);
        List<ApprovalRecord> nextApprovals = new ArrayList<>(snap.approvals());
        nextApprovals.add(approval);
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), nextTasks, nextApprovals,
                snap.backtestRuns(), snap.auditEvents());
        persist(next.withAudit(audit(t.workspaceId(), "TASK_APPROVED", "TASK", taskId,
                "人工批准回测任务（selfApproval=" + approval.selfApproval()
                        + "；批准后仍需人工点击启动）")));
        return approved;
    }

    /** OWNER/ADMIN rejection of a pending-approval task. */
    public synchronized AgentTask rejectTask(String taskId, String reason) {
        ensureCanApprove();                              // 403 (OWNER/ADMIN only)
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, taskId);     // 404 + isolation
        if (!AgentTask.STATUS_PENDING_APPROVAL.equals(t.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有处于 PENDING_APPROVAL 的任务才能被拒绝");
        }
        String now = Instant.now().toString();
        AgentTask rejected = new AgentTask(t.id(), t.workspaceId(), t.sessionId(),
                t.draftId(), t.snapshotId(), t.strategyId(), t.versionNumber(),
                t.type(), t.datasetId(), t.name(), AgentTask.STATUS_REJECTED,
                t.requestedByMemberId(), t.createdAt(), now, t.cancelledAt(),
                t.cancelReason(), t.attemptCount());
        ApprovalRecord approval = new ApprovalRecord(UUID.randomUUID().toString(),
                t.workspaceId(), ApprovalRecord.ENTITY_TASK, taskId,
                ApprovalRecord.DECISION_REJECTED, currentMemberId(),
                reason == null ? "" : truncate(reason, MAX_REASON),
                currentMemberId().equals(t.requestedByMemberId()), now);
        List<AgentTask> nextTasks = new ArrayList<>(snap.tasks());
        nextTasks.replaceAll(x -> x.id().equals(taskId) ? rejected : x);
        List<ApprovalRecord> nextApprovals = new ArrayList<>(snap.approvals());
        nextApprovals.add(approval);
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), nextTasks, nextApprovals,
                snap.backtestRuns(), snap.auditEvents());
        persist(next.withAudit(audit(t.workspaceId(), "TASK_REJECTED", "TASK", taskId,
                "人工拒绝回测任务")));
        return rejected;
    }

    /**
     * The SECOND independent manual gate: the user clicks "启动已批准回测".
     * Allowed only from APPROVED (first run) or QUEUED (retry).
     *
     * <p>Concurrency design: the full backtest computation is NEVER executed
     * while holding the store lock. {@code beginStart} (a short critical
     * section) validates, allocates/consumes the attempt, REGISTERS the
     * per-attempt cancellation signal, persists the RUNNING state +
     * TASK_STARTED audit and releases the lock; the runner then executes
     * OUTSIDE the lock polling that signal; {@code finishStart} (a second
     * short critical section) reads the persisted state and conditionally
     * merges the result — an attempt the user cancelled meanwhile (CANCELLED
     * persisted by cancelTask) is NEVER overwritten by SUCCEEDED/FAILED. The
     * outer finally removes the signal (same key AND object) AFTER the whole
     * runner + conditional-merge lifecycle, so no flag outlives its attempt —
     * even when the executor throws (converged to a controlled FAILED below)
     * or finishStart itself throws.
     */
    public TaskStartResult startTask(String taskId) {
        StartedRun started;
        synchronized (this) {
            started = beginStart(taskId);
        }
        try {
            BacktestRunnerService.BacktestResult result;
            try {
                result = executor.run(started.snapshot(), started.datasetId(),
                        started.cancelFlag()::get);
            } catch (RuntimeException | Error e) {
                // Executor failure: converge to a CONTROLLED FAILED with a
                // fixed, sanitised error — the exception message, class path
                // or stack are never written to the response, disk or audit.
                result = executorFailure(started, e);
            }
            synchronized (this) {
                return finishStart(started, result);
            }
        } finally {
            // remove(key, flag) removes ONLY this attempt's own signal object —
            // a concurrent cancel of a DIFFERENT attempt can never be affected.
            cancellationFlags.remove(started.key(), started.cancelFlag());
        }
    }

    /** Fixed, sanitised FAILED result for an executor that threw. */
    private BacktestRunnerService.BacktestResult executorFailure(
            StartedRun started, Throwable t) {
        log.warn("Backtest executor failed for task {} attempt {}: {}",
                started.taskId(), started.attempt(), t.getClass().getSimpleName());
        return new BacktestRunnerService.BacktestResult(
                BacktestRun.STATUS_FAILED, started.datasetId(), null, null,
                null, null, 0, 0, null, null, null, null, null, null, null, 0,
                BacktestRunnerService.ERROR_INTERNAL,
                "回测执行异常（本地只读回测，与交易撤单无关）");
    }

    /** Short critical section #1: validate, allocate/consume the attempt and
     *  persist RUNNING + TASK_STARTED atomically. Caller holds the lock. */
    private StartedRun beginStart(String taskId) {
        ensureCanWrite();                                // 403
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, taskId);     // 404 + isolation
        if (!AgentTask.STATUS_APPROVED.equals(t.status())
                && !AgentTask.STATUS_QUEUED.equals(t.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有已批准（APPROVED）或重试排队（QUEUED）的任务才能人工启动");
        }
        StrategySpecSnapshot snapshot = snap.snapshots().stream()
                .filter(s -> s.id().equals(t.snapshotId())
                        && s.workspaceId().equals(t.workspaceId()))
                .findFirst()
                .orElseThrow(() -> TaskCenterApiException.illegalState(
                        "任务缺少不可变规格快照，无法回测"));
        String now = Instant.now().toString();
        String wsId = t.workspaceId();
        int attempt;
        String runId;
        if (AgentTask.STATUS_QUEUED.equals(t.status())) {
            // A retry already created the QUEUED attempt — consume it exactly
            // once; NEVER create another attempt here.
            attempt = t.attemptCount();
            BacktestRun queuedRun = snap.backtestRuns().stream()
                    .filter(r -> r.taskId().equals(taskId)
                            && r.workspaceId().equals(wsId)
                            && r.attemptNumber() == attempt
                            && BacktestRun.STATUS_QUEUED.equals(r.status()))
                    .findFirst()
                    .orElseThrow(() -> TaskCenterApiException.illegalState(
                            "任务处于 QUEUED 但缺少待消费的 QUEUED 回测尝试（attempt="
                                    + attempt + "）"));
            runId = queuedRun.id();
        } else {
            // First manual start from APPROVED: attempt 1 is created here and
            // never before (retry is the ONLY way to reach higher attempts).
            attempt = maxAttempt(snap, taskId) + 1;
            runId = UUID.randomUUID().toString();
        }
        BacktestRun running = new BacktestRun(runId, taskId, wsId, attempt,
                BacktestRun.STATUS_RUNNING, t.datasetId(), null, null, null, null,
                0, 0, null, null, null, null, null, null, null, 0, null, null,
                now, null);
        AgentTask runningTask = new AgentTask(t.id(), wsId, t.sessionId(),
                t.draftId(), t.snapshotId(), t.strategyId(), t.versionNumber(),
                t.type(), t.datasetId(), t.name(), AgentTask.STATUS_RUNNING,
                t.requestedByMemberId(), t.createdAt(), now, t.cancelledAt(),
                t.cancelReason(), attempt);
        List<AgentTask> nextTasks = new ArrayList<>(snap.tasks());
        nextTasks.replaceAll(x -> x.id().equals(taskId) ? runningTask : x);
        List<BacktestRun> nextRuns = new ArrayList<>(snap.backtestRuns());
        if (BacktestRun.STATUS_QUEUED.equals(t.status())) {
            nextRuns.replaceAll(x -> x.id().equals(runId) ? running : x);
        } else {
            nextRuns.add(running);
        }
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), nextTasks, snap.approvals(), nextRuns,
                snap.auditEvents());
        // Register the cancellation signal UNDER THE LOCK, before the RUNNING
        // state is persisted: once the lock is released the runner may start
        // polling at any moment, and a concurrent cancelTask must always find
        // the flag already present. A QUEUED attempt never gets a flag (it has
        // no runner) — QUEUED cancellation is a pure state transition.
        String key = taskId + ":" + attempt;
        java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        cancellationFlags.put(key, cancelFlag);
        Snapshot startedSnap = next.withAudit(audit(wsId, "TASK_STARTED",
                "TASK", taskId, "人工启动" + (attempt > 1 ? "重试" : "已批准")
                        + "回测（attempt=" + attempt + "，本地只读回测）"));
        try {
            persist(startedSnap);
        } catch (RuntimeException e) {
            // Persistence of the RUNNING state + TASK_STARTED audit failed —
            // the attempt NEVER started, so its cancellation signal must be
            // removed again BEFORE the exception propagates: removal uses the
            // SAME key AND the SAME AtomicBoolean object, so another attempt's
            // signal is never touched and the whole table is never cleared.
            // The task stays APPROVED / QUEUED on disk with no RUNNING half
            // state and no spurious audit; a later manual start retries the
            // same lifecycle cleanly.
            cancellationFlags.remove(key, cancelFlag);
            throw e;
        }
        return new StartedRun(wsId, taskId, runId, attempt, now,
                t.datasetId(), snapshot, cancelFlag);
    }

    /** Short critical section #2: conditionally merge the completed result.
     *  Caller holds the lock. A persisted CANCELLED state (user cancelled the
     *  attempt while it ran) is never overwritten and no completion audit is
     *  added — TASK_CANCELLED was already recorded by cancelTask. */
    private TaskStartResult finishStart(StartedRun started,
                                        BacktestRunnerService.BacktestResult result) {
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, started.taskId());
        BacktestRun current = snap.backtestRuns().stream()
                .filter(r -> r.id().equals(started.runId()))
                .findFirst()
                .orElseThrow(() -> TaskCenterApiException.illegalState(
                        "回测尝试记录丢失：" + started.runId()));
        if (AgentTask.STATUS_CANCELLED.equals(t.status())
                || BacktestRun.STATUS_CANCELLED.equals(current.status())) {
            // The user cancelled this attempt while it was running; keep the
            // persisted CANCELLED state + its audit — never overwrite it.
            return new TaskStartResult(t, current);
        }
        String finishedAt = Instant.now().toString();
        // Replay provenance is owned by the server-side task binding.  The
        // executor supplies computed metrics/orders, never snapshot/dataset
        // identity or their hashes.
        String templateType = started.snapshot().templateType();
        String snapshotHash = started.snapshot().contentHash();
        DatasetCatalog.Dataset finishedDataset = catalog.load(started.datasetId());
        String datasetHash = finishedDataset == null ? null : finishedDataset.contentHash();
        String executionTiming = StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(templateType)
                ? "EVENT_AVAILABLE->NEXT_BAR_OPEN" : "BAR_CLOSE->NEXT_BAR_OPEN";
        BacktestRun finished = new BacktestRun(current.id(), current.taskId(),
                current.workspaceId(), started.attempt(), result.status(),
                started.datasetId(), finishedDataset == null ? result.instrument()
                : finishedDataset.instrument(), finishedDataset == null ? result.timeframe()
                : finishedDataset.timeframe(), finishedDataset == null ? result.periodStart()
                : finishedDataset.periodStart(), finishedDataset == null ? result.periodEnd()
                : finishedDataset.periodEnd(), finishedDataset == null ? result.inputBars()
                : finishedDataset.bars().size(),
                result.trades(), result.grossReturnPct(), result.netReturnPct(),
                result.maxDrawdownPct(), result.winRate(), result.feeAssumptionBps(),
                result.slippageAssumptionBps(), result.sampleOutStatus(),
                result.durationMs(), result.errorCode(), result.errorMessage(),
                started.startedAt(), finishedAt, templateType, snapshotHash, datasetHash,
                executionTiming, result.orders() == null ? List.of() : result.orders(),
                BacktestRun.CURRENT_SCHEMA_VERSION);
        if (!isValidBacktestRun(finished, snap.tasks(), snap.snapshots())
                || !(BacktestRun.STATUS_SUCCEEDED.equals(finished.status())
                || BacktestRun.STATUS_FAILED.equals(finished.status()))) {
            log.warn("Backtest executor returned an inconsistent result for task {} attempt {}; "
                            + "failing closed before persistence",
                    started.taskId(), started.attempt());
            finished = new BacktestRun(current.id(), current.taskId(), current.workspaceId(),
                    started.attempt(), BacktestRun.STATUS_FAILED, started.datasetId(),
                    finishedDataset == null ? null : finishedDataset.instrument(),
                    finishedDataset == null ? null : finishedDataset.timeframe(),
                    finishedDataset == null ? null : finishedDataset.periodStart(),
                    finishedDataset == null ? null : finishedDataset.periodEnd(),
                    finishedDataset == null ? 0 : finishedDataset.bars().size(),
                    0, null, null, null, null, null, null, null, 0,
                    BacktestRunnerService.ERROR_INVALID_RESULT,
                    "回测执行结果未通过一致性校验（本地只读回测，与交易撤单无关）",
                    started.startedAt(), finishedAt, templateType, snapshotHash, datasetHash,
                    executionTiming, List.of(), BacktestRun.CURRENT_SCHEMA_VERSION);
        }
        boolean succeeded = BacktestRun.STATUS_SUCCEEDED.equals(finished.status());
        AgentTask done = new AgentTask(t.id(), t.workspaceId(), t.sessionId(),
                t.draftId(), t.snapshotId(), t.strategyId(), t.versionNumber(),
                t.type(), t.datasetId(), t.name(),
                succeeded ? AgentTask.STATUS_SUCCEEDED : AgentTask.STATUS_FAILED,
                t.requestedByMemberId(), t.createdAt(), finishedAt,
                t.cancelledAt(), t.cancelReason(), started.attempt());
        List<AgentTask> finalTasks = new ArrayList<>(snap.tasks());
        finalTasks.replaceAll(x -> x.id().equals(started.taskId()) ? done : x);
        List<BacktestRun> finalRuns = new ArrayList<>(snap.backtestRuns());
        BacktestRun completed = finished;
        finalRuns.replaceAll(x -> x.id().equals(started.runId()) ? completed : x);
        Snapshot finalSnap = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), finalTasks, snap.approvals(), finalRuns,
                snap.auditEvents());
        persist(finalSnap.withAudit(audit(started.workspaceId(),
                succeeded ? "TASK_RUN_SUCCEEDED" : "TASK_RUN_FAILED",
                "TASK", started.taskId(),
                succeeded
                        ? "回测完成（attempt=" + started.attempt() + "，trades="
                        + finished.trades() + "，netReturnPct=" + finished.netReturnPct() + "）"
                        : "回测失败（attempt=" + started.attempt()
                        + "，code=" + finished.errorCode() + "）")));
        return new TaskStartResult(done, finished);
    }

    /** The in-flight attempt facts captured under the lock in beginStart. */
    private record StartedRun(String workspaceId, String taskId, String runId,
                              int attempt, String startedAt, String datasetId,
                              StrategySpecSnapshot snapshot,
                              java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        String key() {
            return taskId + ":" + attempt;
        }
    }

    /** TEST-ONLY: the number of live cancellation signals. It MUST be 0 between
     *  runs — leaked signals (e.g. QUEUED cancels or executor failures leaving
     *  flags behind) are bugs. No HTTP endpoint exposes this. */
    int liveCancellationSignalsForTests() {
        return cancellationFlags.size();
    }

    /**
     * LOCAL task cancellation (explicitly unrelated to trading order
     * cancellation). Allowed on QUEUED / RUNNING tasks; a RUNNING task is
     * cancelled cooperatively (the runner polls the cancellation flag) and the
     * full audit trail is preserved.
     */
    public synchronized AgentTask cancelTask(String taskId, String reason) {
        ensureCanWrite();                                // 403
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, taskId);     // 404 + isolation
        if (!AgentTask.STATUS_RUNNING.equals(t.status())
                && !AgentTask.STATUS_QUEUED.equals(t.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有 QUEUED / RUNNING 的任务才能取消（取消仅指本地任务取消，与交易撤单无关）");
        }
        // Signal the in-flight runner ONLY when this attempt really is
        // RUNNING: beginStart registered its flag under the lock, so it is
        // already present here (no creation race). A QUEUED attempt has no
        // runner and MUST NOT create a flag — that would leak an entry that
        // no startTask ever removes. This runs UNDER the store lock while the
        // runner executes OUTSIDE it, so a genuinely RUNNING attempt can be
        // cancelled from another HTTP request. The flag is removed by
        // startTask's outer finally; after a crash the repair deterministically
        // cancels any RUNNING attempt, so no stale signal survives.
        if (AgentTask.STATUS_RUNNING.equals(t.status())) {
            java.util.concurrent.atomic.AtomicBoolean flag =
                    cancellationFlags.get(taskId + ":" + t.attemptCount());
            if (flag != null) {
                flag.set(true);
            }
        }
        String now = Instant.now().toString();
        AgentTask cancelled = new AgentTask(t.id(), t.workspaceId(), t.sessionId(),
                t.draftId(), t.snapshotId(), t.strategyId(), t.versionNumber(),
                t.type(), t.datasetId(), t.name(), AgentTask.STATUS_CANCELLED,
                t.requestedByMemberId(), t.createdAt(), now, now,
                reason == null || reason.isBlank() ? "用户取消" : truncate(reason, MAX_REASON),
                t.attemptCount());
        List<AgentTask> nextTasks = new ArrayList<>(snap.tasks());
        nextTasks.replaceAll(x -> x.id().equals(taskId) ? cancelled : x);
        // The in-flight attempt (RUNNING or QUEUED) is cancelled too, keeping
        // its record + the audit trail (cooperative cancellation semantics).
        List<BacktestRun> nextRuns = new ArrayList<>(snap.backtestRuns());
        for (int i = 0; i < nextRuns.size(); i++) {
            BacktestRun r = nextRuns.get(i);
            if (r.taskId().equals(taskId) && (BacktestRun.STATUS_RUNNING.equals(r.status())
                    || BacktestRun.STATUS_QUEUED.equals(r.status()))) {
                nextRuns.set(i, new BacktestRun(r.id(), r.taskId(), r.workspaceId(),
                        r.attemptNumber(), BacktestRun.STATUS_CANCELLED, r.datasetId(),
                        r.instrument(), r.timeframe(), r.periodStart(), r.periodEnd(),
                        r.inputBars(), r.trades(), r.grossReturnPct(), r.netReturnPct(),
                        r.maxDrawdownPct(), r.winRate(), r.feeAssumptionBps(),
                        r.slippageAssumptionBps(), r.sampleOutStatus(), r.durationMs(),
                        BacktestRunnerService.ERROR_CANCELLED,
                        "本地任务取消（与交易撤单无关）", r.startedAt(), now));
            }
        }
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), nextTasks, snap.approvals(), nextRuns,
                snap.auditEvents());
        persist(next.withAudit(audit(t.workspaceId(), "TASK_CANCELLED", "TASK", taskId,
                "本地任务已取消（与交易撤单无关）")));
        return cancelled;
    }

    /**
     * Retry a FAILED / CANCELLED task: creates a NEW attempt (max+1) in QUEUED
     * state; old results are NEVER overwritten and the task needs a new manual
     * start click (no auto-start).
     */
    public synchronized AgentTask retryTask(String taskId) {
        ensureCanWrite();                                // 403
        Snapshot snap = loadAndRepair();
        AgentTask t = taskByIdOrThrow(snap, taskId);     // 404 + isolation
        if (!AgentTask.STATUS_FAILED.equals(t.status())
                && !AgentTask.STATUS_CANCELLED.equals(t.status())) {
            throw TaskCenterApiException.illegalState(
                    "只有 FAILED / CANCELLED 的任务才能重试");
        }
        String now = Instant.now().toString();
        int attempt = maxAttempt(snap, taskId) + 1;
        AgentTask queued = new AgentTask(t.id(), t.workspaceId(), t.sessionId(),
                t.draftId(), t.snapshotId(), t.strategyId(), t.versionNumber(),
                t.type(), t.datasetId(), t.name(), AgentTask.STATUS_QUEUED,
                t.requestedByMemberId(), t.createdAt(), now, null, null, attempt);
        List<AgentTask> nextTasks = new ArrayList<>(snap.tasks());
        nextTasks.replaceAll(x -> x.id().equals(taskId) ? queued : x);
        List<BacktestRun> nextRuns = new ArrayList<>(snap.backtestRuns());
        nextRuns.add(new BacktestRun(UUID.randomUUID().toString(), taskId,
                t.workspaceId(), attempt, BacktestRun.STATUS_QUEUED, t.datasetId(),
                null, null, null, null, 0, 0, null, null, null, null, null, null,
                null, 0, null, null, now, null));
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                snap.agentSteps(), snap.checkpoints(), snap.drafts(), snap.snapshots(),
                snap.validationReports(), nextTasks, snap.approvals(), nextRuns,
                snap.auditEvents());
        persist(next.withAudit(audit(t.workspaceId(), "TASK_RETRIED", "TASK", taskId,
                "重试已创建新 attempt（attempt=" + attempt
                        + "，不覆盖旧结果；需再次人工启动）")));
        return queued;
    }

    // ================================================================== //
    // Agent harness persistence (runs / steps / checkpoints)
    // ================================================================== //

    /** Persist a new AgentRun (with its steps + optional checkpoint in the
     *  same atomic write). */
    public synchronized AgentRunDetail persistAgentRun(AgentRun run,
                                                       List<AgentStep> steps,
                                                       RunCheckpoint checkpoint) {
        Snapshot snap = loadAndRepair();
        List<AgentRun> nextRuns = new ArrayList<>(snap.agentRuns());
        nextRuns.add(run);
        List<AgentStep> nextSteps = new ArrayList<>(snap.agentSteps());
        nextSteps.addAll(steps);
        List<RunCheckpoint> nextCheckpoints = new ArrayList<>(snap.checkpoints());
        if (checkpoint != null) nextCheckpoints.add(checkpoint);
        Snapshot next = new Snapshot(snap.sessions(), snap.turns(), nextRuns, nextSteps,
                nextCheckpoints, snap.drafts(), snap.snapshots(),
                snap.validationReports(), snap.tasks(), snap.approvals(),
                snap.backtestRuns(), snap.auditEvents());
        persist(next);
        return new AgentRunDetail(run, steps, checkpoint);
    }

    // ================================================================== //
    // Server-side RBAC + validation
    // ================================================================== //

    /** OWNER / ADMIN / TRADER may create sessions, edit drafts, request
     *  validation, submit approval, create/start/cancel/retry tasks;
     *  VIEWER is read-only. */
    private void ensureCanWrite() {
        String role = currentRole();
        if (TeamStore.ROLE_VIEWER.equals(role)) {
            throw TaskCenterApiException.forbidden(
                    "当前角色（VIEWER）仅可查看任务中心内容，不可写入");
        }
    }

    /** Only OWNER / ADMIN may approve or reject (TRADER never approves). */
    private void ensureCanApprove() {
        String role = currentRole();
        if (!TeamStore.ROLE_OWNER.equals(role) && !TeamStore.ROLE_ADMIN.equals(role)) {
            throw TaskCenterApiException.forbidden(
                    "当前角色（" + role + "）无批准/拒绝权限（仅 OWNER / ADMIN）");
        }
    }

    private String currentRole() {
        TeamMember actor = teamStore.currentActor();
        return actor == null ? TeamStore.ROLE_VIEWER : actor.role();
    }

    private String currentMemberId() {
        TeamMember actor = teamStore.currentActor();
        return actor == null ? null : actor.id();
    }

    /** Validate a session title: trimmed, 1..64 chars, no control characters. */
    static String validateSessionTitle(String raw) {
        if (raw == null) {
            throw TaskCenterApiException.invalidName("会话标题不能为空");
        }
        String title = raw.trim();
        if (title.isEmpty()) {
            throw TaskCenterApiException.invalidName("会话标题不能为空");
        }
        if (title.length() > 64) {
            throw TaskCenterApiException.invalidName("会话标题过长（最多 64 个字符）");
        }
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw TaskCenterApiException.invalidName("会话标题包含不允许的控制字符");
            }
        }
        return title;
    }

    /** Validate a user turn content: trimmed, 1..{@value #MAX_CONTENT} chars,
     *  no control characters, NO credential-shaped content (rejected
     *  fail-closed — never stored, never logged). */
    public static String validateTurnContent(String raw) {
        if (raw == null) {
            throw TaskCenterApiException.invalidContent("消息内容不能为空");
        }
        String content = raw.trim();
        if (content.isEmpty()) {
            throw TaskCenterApiException.invalidContent("消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT) {
            throw TaskCenterApiException.invalidContent(
                    "消息内容过长（最多 " + MAX_CONTENT + " 个字符）");
        }
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw TaskCenterApiException.invalidContent(
                        "消息内容包含不允许的控制字符");
            }
        }
        if (StrategySpecValidator.containsBannedContent(content)) {
            throw TaskCenterApiException.invalidContent(
                    "消息内容包含凭证/代码/脚本等敏感形态，已拒绝保存。请移除后重试。");
        }
        return content;
    }

    /** Validate a reason: trimmed, at most {@value #MAX_REASON} chars. */
    static String validateReason(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.trim();
        if (s.length() > MAX_REASON) {
            throw TaskCenterApiException.invalidBody(
                    "理由过长（最多 " + MAX_REASON + " 个字符）");
        }
        return s;
    }

    // ================================================================== //
    // Draft field application (patch + question answers)
    // ================================================================== //

    /** Apply a validated patch to the draft, re-compute completeness +
     *  missing fields and append the field evidence. */
    public static StrategySpecDraft applyPatch(StrategySpecDraft d,
                                        TaskCenterDtos.DraftPatch patch,
                                        String sourceTurnId) {
        String now = Instant.now().toString();
        List<FieldEvidence> evidence = new ArrayList<>(d.evidence() == null
                ? List.of() : d.evidence());
        SpecParameters params = d.parameters() == null ? new SpecParameters(null, null,
                null, null, null, null) : d.parameters();
        String templateType = patch.templateType() == null
                ? d.templateType() : patch.templateType();
        if (!StrategySpecDraft.TEMPLATE_WHITELIST.contains(templateType)) {
            throw TaskCenterApiException.invalidParameter("templateType 不在白名单中");
        }
        boolean legacyParameterPatch = patch.fastWindow() != null || patch.slowWindow() != null
                || patch.positionSize() != null || patch.stopLossPct() != null
                || patch.feeBps() != null || patch.slippageBps() != null;
        if (patch.parameters() != null && legacyParameterPatch) {
            throw TaskCenterApiException.invalidParameter(
                    "parameters 与旧 SMA 扁平参数不可混用");
        }
        if (!StrategySpecDraft.TEMPLATE_SMA_CROSS.equals(templateType)
                && legacyParameterPatch) {
            throw TaskCenterApiException.invalidParameter(
                    "旧扁平参数仅适用于 SMA_CROSS");
        }
        if (patch.parameters() != null) {
            if (!templateType.equals(patch.parameters().type())) {
                throw TaskCenterApiException.invalidParameter(
                        "parameters.type 必须与 templateType 一致");
            }
            SpecParameters.EventSignal event = patch.parameters().eventSignal();
            if (event != null && (event.confirmationId() != null
                    || event.confirmedByMemberId() != null || event.confirmedAt() != null)) {
                throw TaskCenterApiException.invalidParameter(
                        "普通 PATCH 不得写入 EVENT 确认字段；请调用独立确认接口");
            }
            params = patch.parameters();
            evidence = withEvidence(evidence, "parameters", sourceTurnId,
                    truncate(params.toString(), 200), now);
            for (String field : SpecFieldHelpers.requiredFields(templateType)) {
                Object value = params.canonicalMap().get(field);
                if (value != null) {
                    evidence = withEvidence(evidence, field, sourceTurnId,
                            truncate(String.valueOf(value), 200), now);
                }
            }
        } else if (!templateType.equals(d.templateType())) {
            params = switch (templateType) {
                case StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT ->
                        SpecParameters.breakout(null, null, null, null, null, null, null);
                case StrategySpecDraft.TEMPLATE_EVENT_SIGNAL -> SpecParameters.event(
                        new SpecParameters.EventSignal(null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null,
                                null, null, null, null));
                default -> SpecParameters.smaV2(null, null, null, null, null, null);
            };
        }
        String name = d.name();
        List<String> instruments = d.instruments();
        String timeframe = d.timeframe();
        String entry = d.entryCondition();
        String exit = d.exitCondition();
        String risk = d.riskLimits();
        String assumptions = d.backtestAssumptions();

        if (patch.name() != null) {
            name = SpecFieldHelpers.validateName(patch.name());
            evidence = withEvidence(evidence, "name", sourceTurnId, name, now);
        }
        if (patch.instruments() != null) {
            instruments = SpecFieldHelpers.parseInstruments(String.join(" ",
                    patch.instruments()));
            evidence = withEvidence(evidence, "instruments", sourceTurnId,
                    String.join(",", instruments), now);
        }
        if (patch.timeframe() != null) {
            timeframe = SpecFieldHelpers.parseTimeframe(patch.timeframe());
            evidence = withEvidence(evidence, "timeframe", sourceTurnId, timeframe, now);
        }
        if (patch.fastWindow() != null) {
            params = smaParametersLike(params, patch.fastWindow(), params.slowWindow(),
                    params.positionSize(), params.stopLossPct(), params.feeBps(),
                    params.slippageBps());
            evidence = withEvidence(evidence, "fastWindow", sourceTurnId,
                    String.valueOf(patch.fastWindow()), now);
        }
        if (patch.slowWindow() != null) {
            params = smaParametersLike(params, params.fastWindow(), patch.slowWindow(),
                    params.positionSize(), params.stopLossPct(), params.feeBps(),
                    params.slippageBps());
            evidence = withEvidence(evidence, "slowWindow", sourceTurnId,
                    String.valueOf(patch.slowWindow()), now);
        }
        if (patch.positionSize() != null) {
            params = smaParametersLike(params, params.fastWindow(), params.slowWindow(),
                    patch.positionSize(), params.stopLossPct(), params.feeBps(),
                    params.slippageBps());
            evidence = withEvidence(evidence, "positionSize", sourceTurnId,
                    String.valueOf(patch.positionSize()), now);
        }
        if (patch.stopLossPct() != null) {
            params = smaParametersLike(params, params.fastWindow(), params.slowWindow(),
                    params.positionSize(), patch.stopLossPct(), params.feeBps(),
                    params.slippageBps());
            evidence = withEvidence(evidence, "stopLossPct", sourceTurnId,
                    String.valueOf(patch.stopLossPct()), now);
        }
        if (patch.feeBps() != null) {
            params = smaParametersLike(params, params.fastWindow(), params.slowWindow(),
                    params.positionSize(), params.stopLossPct(), patch.feeBps(),
                    params.slippageBps());
            evidence = withEvidence(evidence, "feeBps", sourceTurnId,
                    String.valueOf(patch.feeBps()), now);
        }
        if (patch.slippageBps() != null) {
            params = smaParametersLike(params, params.fastWindow(), params.slowWindow(),
                    params.positionSize(), params.stopLossPct(), params.feeBps(),
                    patch.slippageBps());
            evidence = withEvidence(evidence, "slippageBps", sourceTurnId,
                    String.valueOf(patch.slippageBps()), now);
        }
        if (patch.entryCondition() != null) {
            entry = SpecFieldHelpers.validateText(patch.entryCondition(),
                    SpecFieldHelpers.MAX_TEXT, "入场条件");
            evidence = withEvidence(evidence, "entryCondition", sourceTurnId, entry, now);
        }
        if (patch.exitCondition() != null) {
            exit = SpecFieldHelpers.validateText(patch.exitCondition(),
                    SpecFieldHelpers.MAX_TEXT, "出场条件");
            evidence = withEvidence(evidence, "exitCondition", sourceTurnId, exit, now);
        }
        if (patch.riskLimits() != null) {
            risk = SpecFieldHelpers.validateText(patch.riskLimits(),
                    SpecFieldHelpers.MAX_TEXT, "风险限制");
            evidence = withEvidence(evidence, "riskLimits", sourceTurnId, risk, now);
        }
        if (patch.backtestAssumptions() != null) {
            assumptions = SpecFieldHelpers.validateText(patch.backtestAssumptions(),
                    SpecFieldHelpers.MAX_ASSUMPTIONS, "回测假设");
            evidence = withEvidence(evidence, "backtestAssumptions", sourceTurnId,
                    assumptions, now);
        }
        StrategySpecDraft updated = new StrategySpecDraft(d.id(), d.workspaceId(),
                d.sessionId(), d.strategyId(), d.baseVersionNumber(), d.seedId(),
                name, templateType, instruments, timeframe, params, entry, exit,
                risk, assumptions, evidence, 0, List.of(), d.status(),
                d.createdByMemberId(), d.createdAt(), now);
        validateParameterBounds(templateType, params);   // 400 INVALID_PARAMETER
        return recompute(updated);
    }

    private static SpecParameters smaParametersLike(SpecParameters current,
                                                     Integer fastWindow,
                                                     Integer slowWindow,
                                                     Double positionSize,
                                                     Double stopLossPct,
                                                     Integer feeBps,
                                                     Integer slippageBps) {
        return current != null && current.legacy()
                ? new SpecParameters(fastWindow, slowWindow, positionSize, stopLossPct,
                feeBps, slippageBps)
                : SpecParameters.smaV2(fastWindow, slowWindow, positionSize, stopLossPct,
                feeBps, slippageBps);
    }

    /** Deterministic bounds of the SMA_CROSS parameters (fail-closed on the
     *  PATCH path; the Validator independently re-checks on validate). */
    private static void validateParameterBounds(String templateType, SpecParameters p) {
        if (!templateType.equals(p.type())) {
            throw TaskCenterApiException.invalidParameter(
                    "parameters.type 必须与 templateType 一致");
        }
        if (StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(templateType)
                && p.eventSignal() != null
                && p.eventSignal().confirmationId() == null
                && p.eventSignal().confirmedByMemberId() == null
                && p.eventSignal().confirmedAt() == null) {
            validateFiniteCommonParameters(p);
            return;
        }
        if (!StrategySpecDraft.TEMPLATE_SMA_CROSS.equals(templateType)) {
            String issue = BacktestRunnerService.parametersValid(templateType, p,
                    p.eventSignal() == null ? null : p.eventSignal().relatedInstrument());
            if (issue != null) throw TaskCenterApiException.invalidParameter(issue);
            return;
        }
        if (p.fastWindow() != null && (p.fastWindow() < 2 || p.fastWindow() > 100)) {
            throw TaskCenterApiException.invalidParameter("fastWindow 需为 2–100 的整数");
        }
        if (p.slowWindow() != null && (p.slowWindow() < 3 || p.slowWindow() > 300)) {
            throw TaskCenterApiException.invalidParameter("slowWindow 需为 3–300 的整数");
        }
        if (p.positionSize() != null && (p.positionSize() < 0 || p.positionSize() > 1)) {
            throw TaskCenterApiException.invalidParameter("positionSize 需为 0–1");
        }
        if (p.stopLossPct() != null && (p.stopLossPct() < 0 || p.stopLossPct() > 30)) {
            throw TaskCenterApiException.invalidParameter("stopLossPct 需为 0–30");
        }
        if (p.feeBps() != null && (p.feeBps() < 0 || p.feeBps() > 100)) {
            throw TaskCenterApiException.invalidParameter("feeBps 需为 0–100 的整数");
        }
        if (p.slippageBps() != null && (p.slippageBps() < 0 || p.slippageBps() > 100)) {
            throw TaskCenterApiException.invalidParameter("slippageBps 需为 0–100 的整数");
        }
    }

    private static void validateFiniteCommonParameters(SpecParameters p) {
        if (p.positionSize() != null && (!Double.isFinite(p.positionSize())
                || p.positionSize() < 0 || p.positionSize() > 1)) {
            throw TaskCenterApiException.invalidParameter("positionSize 需为 0–1");
        }
        if (p.stopLossPct() != null && (!Double.isFinite(p.stopLossPct())
                || p.stopLossPct() < 0 || p.stopLossPct() > 30)) {
            throw TaskCenterApiException.invalidParameter("stopLossPct 需为 0–30");
        }
        if (p.feeBps() != null && (p.feeBps() < 0 || p.feeBps() > 100)) {
            throw TaskCenterApiException.invalidParameter("feeBps 需为 0–100 的整数");
        }
        if (p.slippageBps() != null && (p.slippageBps() < 0 || p.slippageBps() > 100)) {
            throw TaskCenterApiException.invalidParameter("slippageBps 需为 0–100 的整数");
        }
    }

    /** Fail-closed: any banned content in the declaration fields (code /
     *  script / prompt / tool permission / credential shapes) rejects the
     *  patch — the Validator independently re-checks on validate. */
    private static void rejectBannedContent(StrategySpecDraft d) {
        for (String field : List.of("name", "entryCondition", "exitCondition",
                "riskLimits", "backtestAssumptions")) {
            String value = switch (field) {
                case "name" -> d.name();
                case "entryCondition" -> d.entryCondition();
                case "exitCondition" -> d.exitCondition();
                case "riskLimits" -> d.riskLimits();
                default -> d.backtestAssumptions();
            };
            if (value != null && StrategySpecValidator.containsBannedContent(value)) {
                throw TaskCenterApiException.invalidParameter(
                        "声明内容包含被禁止的代码/脚本/Prompt/工具权限/凭证形态内容（字段："
                                + field + "）");
            }
        }
    }

    /** Keep one evidence entry per field (the newest citedAt wins). */
    private static List<FieldEvidence> withEvidence(List<FieldEvidence> evidence,
                                                    String field, String sourceTurnId,
                                                    String value, String now) {
        List<FieldEvidence> out = new ArrayList<>();
        for (FieldEvidence e : evidence) {
            if (!e.field().equals(field)) out.add(e);
        }
        out.add(new FieldEvidence(field, sourceTurnId, truncate(value, 200), now));
        return out;
    }

    private static String eventConfirmationSummary(SpecParameters.EventSignal event) {
        String canonical = String.join("\n",
                safe(event.eventId()), safe(event.sourceId()), safe(event.contentHash()),
                safe(event.originalPublishedAt()), safe(event.fetchedAt()),
                safe(event.availableAt()), safe(event.validFrom()), safe(event.validUntil()),
                safe(event.relatedInstrument()), safe(event.direction()),
                safe(event.sourceStatus()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return "EVENT_FACTS_SHA256=" + hex;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasAuthoritativeEventConfirmation(
            StrategySpecDraft draft, List<ApprovalRecord> approvals) {
        if (!StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(draft.templateType())
                || draft.parameters() == null || draft.parameters().eventSignal() == null) {
            return true;
        }
        SpecParameters.EventSignal event = draft.parameters().eventSignal();
        if (!nonBlank(event.confirmationId(), event.confirmedByMemberId(), event.confirmedAt())) {
            return false;
        }
        String expectedSummary = eventConfirmationSummary(event);
        return approvals.stream().anyMatch(a -> event.confirmationId().equals(a.id())
                && draft.workspaceId().equals(a.workspaceId())
                && ApprovalRecord.ENTITY_EVENT_EVIDENCE.equals(a.entityType())
                && draft.id().equals(a.entityId())
                && ApprovalRecord.DECISION_CONFIRMED.equals(a.decision())
                && event.confirmedByMemberId().equals(a.decidedByMemberId())
                && event.confirmedAt().equals(a.decidedAt())
                && a.selfApproval() == event.confirmedByMemberId()
                .equals(draft.createdByMemberId())
                && expectedSummary.equals(a.reason()));
    }

    private static void ensureAuthoritativeEventConfirmation(
            StrategySpecDraft draft, List<ApprovalRecord> approvals) {
        if (StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(draft.templateType())
                && !hasAuthoritativeEventConfirmation(draft, approvals)) {
            throw TaskCenterApiException.evidenceMissing(
                    "EVENT_SIGNAL 必须先由 OWNER/ADMIN 独立确认来源事实");
        }
    }

    private static StrategySpecDraft clearUntrustedEventConfirmation(StrategySpecDraft draft) {
        SpecParameters.EventSignal e = draft.parameters().eventSignal();
        SpecParameters parameters = SpecParameters.event(new SpecParameters.EventSignal(
                e.eventId(), e.sourceId(), e.contentHash(), e.originalPublishedAt(),
                e.fetchedAt(), e.availableAt(), e.validFrom(), e.validUntil(),
                e.relatedInstrument(), e.direction(), null, null, null, e.sourceStatus(),
                e.stopLossPct(), e.positionSize(), e.feeBps(), e.slippageBps()));
        List<FieldEvidence> evidence = (draft.evidence() == null ? List.<FieldEvidence>of()
                : draft.evidence()).stream()
                .filter(item -> !Set.of("confirmationId", "confirmedByMemberId", "confirmedAt")
                        .contains(item.field()))
                .toList();
        return recompute(new StrategySpecDraft(draft.id(), draft.workspaceId(),
                draft.sessionId(), draft.strategyId(), draft.baseVersionNumber(),
                draft.seedId(), draft.name(), draft.templateType(), draft.instruments(),
                draft.timeframe(), parameters, draft.entryCondition(), draft.exitCondition(),
                draft.riskLimits(), draft.backtestAssumptions(), evidence, 0, List.of(),
                StrategySpecDraft.STATUS_CO_CREATING, draft.createdByMemberId(),
                draft.createdAt(), draft.updatedAt()));
    }

    /** Recompute completeness + missingFields from the confirmed fields. */
    static StrategySpecDraft recompute(StrategySpecDraft d) {
        return new StrategySpecDraft(d.id(), d.workspaceId(), d.sessionId(),
                d.strategyId(), d.baseVersionNumber(), d.seedId(), d.name(),
                d.templateType(), d.instruments(), d.timeframe(), d.parameters(),
                d.entryCondition(), d.exitCondition(), d.riskLimits(),
                d.backtestAssumptions(), d.evidence(),
                SpecFieldHelpers.completeness(d),
                SpecFieldHelpers.missingFields(d), d.status(),
                d.createdByMemberId(), d.createdAt(), d.updatedAt());
    }

    private static StrategySpecDraft withStatus(StrategySpecDraft d, String status) {
        return new StrategySpecDraft(d.id(), d.workspaceId(), d.sessionId(),
                d.strategyId(), d.baseVersionNumber(), d.seedId(), d.name(),
                d.templateType(), d.instruments(), d.timeframe(), d.parameters(),
                d.entryCondition(), d.exitCondition(), d.riskLimits(),
                d.backtestAssumptions(), d.evidence(), d.completeness(),
                d.missingFields(), status, d.createdByMemberId(), d.createdAt(),
                Instant.now().toString());
    }

    /** Canonical SHA-256 of the frozen snapshot content (tamper-evident). */
    static String contentHash(StrategySpecDraft d) {
        return StrategySpecCanonicalizer.hash(d.name(), d.templateType(), d.instruments(),
                d.timeframe(), d.parameters(), d.entryCondition(), d.exitCondition(),
                d.riskLimits(), d.backtestAssumptions());
    }

    // ================================================================== //
    // Freeze-transaction marker + deterministic recovery
    // ================================================================== //

    /** The recoverable freeze-transaction marker (sits next to the store). */
    record FreezeMarker(
            @JsonProperty("schema") String schema,
            @JsonProperty("draftId") String draftId,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("seedId") String seedId,
            @JsonProperty("name") String name,
            @JsonProperty("templateType") String templateType,
            @JsonProperty("instruments") List<String> instruments,
            @JsonProperty("timeframe") String timeframe,
            @JsonProperty("parameters") SpecParameters parameters,
            @JsonProperty("entryCondition") String entryCondition,
            @JsonProperty("exitCondition") String exitCondition,
            @JsonProperty("riskLimits") String riskLimits,
            @JsonProperty("backtestAssumptions") String backtestAssumptions,
            @JsonProperty("strategyId") String strategyId,
            @JsonProperty("newStrategy") boolean newStrategy,
            @JsonProperty("versionId") String versionId,
            @JsonProperty("snapshotId") String snapshotId,
            @JsonProperty("approvalId") String approvalId,
            @JsonProperty("auditApprovedId") String auditApprovedId,
            @JsonProperty("auditFrozenId") String auditFrozenId,
            @JsonProperty("createdByMemberId") String createdByMemberId,
            @JsonProperty("decidedByMemberId") String decidedByMemberId,
            @JsonProperty("reason") String reason,
            @JsonProperty("createdAt") String createdAt) {
        @JsonCreator
        FreezeMarker {}
    }

    private void writeMarker(FreezeMarker marker) {
        try {
            if (markerFile.getParent() != null) {
                Files.createDirectories(markerFile.getParent());
            }
            Path tmp = markerFile.resolveSibling(markerFile.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), marker);
            try {
                Files.move(tmp, markerFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, markerFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new TaskCenterApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    TaskCenterApiException.CODE_FREEZE_FAILED,
                    "无法写入冻结事务标记：" + e.getClass().getSimpleName());
        }
    }

    private void deleteMarker() {
        try {
            Files.deleteIfExists(markerFile);
        } catch (IOException e) {
            log.warn("approveAndFreeze: could not delete freeze marker ({}); the next load "
                    + "will retry idempotently", e.getClass().getSimpleName());
        }
    }

    private FreezeMarker readMarker() {
        if (!Files.exists(markerFile)) return null;
        try {
            byte[] bytes = Files.readAllBytes(markerFile);
            if (bytes.length == 0) return null;
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) return null;
            if (!FREEZE_TXN_SCHEMA.equals(root.path("schema").asText())) {
                log.warn("freeze marker schema unsupported (kept for manual review)");
                return null;
            }
            return mapper.treeToValue(root, FreezeMarker.class);
        } catch (Exception e) {
            // An unreadable marker is kept (never deleted blindly): a half-frozen
            // state must not be silently dropped.
            log.warn("freeze marker unreadable (kept for manual review): {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Deterministic recovery of an interrupted approve-and-freeze transaction
     * (called at the start of every load). Idempotent:
     * <ul>
     *   <li>if the Module-8 version (by pre-generated id) is missing → replay
     *       the strategies write (create-with-ids or append-with-id);</li>
     *   <li>if the task-center side is missing → replay the snapshot + FROZEN
     *       draft + approval + audit events (pre-generated ids);</li>
     *   <li>delete the marker.</li>
     * </ul>
     * A crash can therefore NEVER leave a half-frozen state: either both sides
     * exist (marker deleted) or the next load completes the transaction.
     */
    private void recoverInterruptedFreeze() {
        FreezeMarker marker = readMarker();
        if (marker == null) return;
        try {
            recoverInterruptedFreeze(marker);
        } catch (RuntimeException e) {
            // The marker is KEPT (never deleted blindly) and the failure is
            // logged — a half-frozen state must never be silently dropped.
            // The store keeps serving; the next load retries deterministically.
            log.warn("recoverInterruptedFreeze: replay failed for draft {} (marker kept): {}",
                    marker.draftId(), e.getClass().getSimpleName());
        }
    }

    private void recoverInterruptedFreeze(FreezeMarker marker) {
        log.warn("recoverInterruptedFreeze: found freeze marker for draft {} — replaying",
                marker.draftId());
        Snapshot snap = readFileSnapshot();
        StrategySpecDraft draft = snap.drafts().stream()
                .filter(d -> d.id().equals(marker.draftId())).findFirst().orElse(null);

        boolean versionExists = strategyStore.hasVersion(marker.versionId());
        if (!versionExists) {
            if (marker.newStrategy()) {
                strategyStore.createRecoveredStrategy(marker.strategyId(), marker.versionId(),
                        marker.workspaceId(), marker.name(), marker.name(),
                        marker.instruments(), marker.timeframe(), marker.entryCondition(),
                        marker.exitCondition(), marker.riskLimits(),
                        marker.createdByMemberId(), marker.createdAt(), marker.createdAt());
            } else {
                strategyStore.appendVersionWithId(marker.strategyId(), marker.versionId(),
                        new com.supertrader.demo.strategy.StrategyDtos.AppendVersionRequest(
                                marker.name(), marker.instruments(), marker.timeframe(),
                                marker.entryCondition(), marker.exitCondition(),
                                marker.riskLimits()),
                        marker.decidedByMemberId());
            }
            log.warn("recoverInterruptedFreeze: replayed strategies write for draft {}",
                    marker.draftId());
        }
        StrategyVersion version = strategyStore.versionById(marker.versionId());
        if (version == null) {
            log.warn("recoverInterruptedFreeze: version still missing after replay for draft {}",
                    marker.draftId());
            return; // retry on the next load
        }

        boolean snapshotExists = snap.snapshots().stream()
                .anyMatch(s -> s.id().equals(marker.snapshotId()));
        if (!snapshotExists) {
            StrategySpecSnapshot snapshot = new StrategySpecSnapshot(marker.snapshotId(),
                    marker.draftId(), marker.workspaceId(), marker.strategyId(),
                    version.versionNumber(), marker.name(), marker.templateType(),
                    marker.instruments(), marker.timeframe(), marker.parameters(),
                    marker.entryCondition(), marker.exitCondition(), marker.riskLimits(),
                    marker.backtestAssumptions(), contentHash(marker), marker.createdAt(),
                    marker.decidedByMemberId());
            ApprovalRecord approval = new ApprovalRecord(marker.approvalId(),
                    marker.workspaceId(), ApprovalRecord.ENTITY_DRAFT, marker.draftId(),
                    ApprovalRecord.DECISION_APPROVED, marker.decidedByMemberId(),
                    marker.reason(),
                    marker.decidedByMemberId().equals(marker.createdByMemberId()),
                    marker.createdAt());
            AuditEvent auditApproved = new AuditEvent(marker.auditApprovedId(),
                    marker.workspaceId(), marker.decidedByMemberId(), "DRAFT_APPROVED",
                    "DRAFT", marker.draftId(),
                    "人工批准草案（selfApproval=" + approval.selfApproval()
                            + "；冻结事务恢复）", marker.createdAt());
            AuditEvent auditFrozen = new AuditEvent(marker.auditFrozenId(),
                    marker.workspaceId(), marker.decidedByMemberId(), "DRAFT_FROZEN",
                    "DRAFT", marker.draftId(),
                    "草案已冻结为不可变规格快照，并关联策略版本 strategyId="
                            + marker.strategyId() + " version=" + version.versionNumber()
                            + "（冻结事务恢复）", marker.createdAt());
            List<StrategySpecDraft> drafts = new ArrayList<>(snap.drafts());
            if (draft != null) {
                drafts.replaceAll(d -> d.id().equals(marker.draftId())
                        ? withStatus(d, StrategySpecDraft.STATUS_FROZEN) : d);
            }
            List<StrategySpecSnapshot> snapshots = new ArrayList<>(snap.snapshots());
            snapshots.add(snapshot);
            List<ApprovalRecord> approvals = new ArrayList<>(snap.approvals());
            approvals.add(approval);
            List<AuditEvent> audits = new ArrayList<>(snap.auditEvents());
            audits.add(auditApproved);
            audits.add(auditFrozen);
            Snapshot next = new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                    snap.agentSteps(), snap.checkpoints(), drafts, snapshots,
                    snap.validationReports(), snap.tasks(), approvals,
                    snap.backtestRuns(), audits);
            persist(next);
            log.warn("recoverInterruptedFreeze: replayed task-center side for draft {}",
                    marker.draftId());
        } else if (draft != null && !StrategySpecDraft.STATUS_FROZEN.equals(draft.status())) {
            List<StrategySpecDraft> drafts = new ArrayList<>(snap.drafts());
            drafts.replaceAll(d -> d.id().equals(marker.draftId())
                    ? withStatus(d, StrategySpecDraft.STATUS_FROZEN) : d);
            persist(new Snapshot(snap.sessions(), snap.turns(), snap.agentRuns(),
                    snap.agentSteps(), snap.checkpoints(), drafts, snap.snapshots(),
                    snap.validationReports(), snap.tasks(), snap.approvals(),
                    snap.backtestRuns(), snap.auditEvents()));
        }
        deleteMarker();
        log.warn("recoverInterruptedFreeze: freeze transaction for draft {} recovered",
                marker.draftId());
    }

    /** Hash the marker's content the same canonical way as the draft. */
    private static String contentHash(FreezeMarker m) {
        return StrategySpecCanonicalizer.hash(m.name(), m.templateType(), m.instruments(),
                m.timeframe(), m.parameters(), m.entryCondition(), m.exitCondition(),
                m.riskLimits(), m.backtestAssumptions());
    }

    // ================================================================== //
    // Snapshot load + deterministic repair
    // ================================================================== //

    /** The full valid state of the store (all 12 arrays). */
    private record Snapshot(
            List<ConversationSession> sessions,
            List<ConversationTurn> turns,
            List<AgentRun> agentRuns,
            List<AgentStep> agentSteps,
            List<RunCheckpoint> checkpoints,
            List<StrategySpecDraft> drafts,
            List<StrategySpecSnapshot> snapshots,
            List<ValidationReport> validationReports,
            List<AgentTask> tasks,
            List<ApprovalRecord> approvals,
            List<BacktestRun> backtestRuns,
            List<AuditEvent> auditEvents) {

        Snapshot withSession(ConversationSession s) {
            List<ConversationSession> ns = new ArrayList<>(sessions);
            ns.add(s);
            return new Snapshot(ns, turns, agentRuns, agentSteps, checkpoints, drafts,
                    snapshots, validationReports, tasks, approvals, backtestRuns, auditEvents);
        }

        Snapshot withTurn(ConversationTurn t) {
            List<ConversationTurn> nt = new ArrayList<>(turns);
            nt.add(t);
            return new Snapshot(sessions, nt, agentRuns, agentSteps, checkpoints, drafts,
                    snapshots, validationReports, tasks, approvals, backtestRuns, auditEvents);
        }

        Snapshot withDraft(StrategySpecDraft d) {
            List<StrategySpecDraft> nd = new ArrayList<>(drafts);
            nd.add(d);
            return new Snapshot(sessions, turns, agentRuns, agentSteps, checkpoints, nd,
                    snapshots, validationReports, tasks, approvals, backtestRuns, auditEvents);
        }

        Snapshot withAudit(AuditEvent e) {
            List<AuditEvent> na = new ArrayList<>(auditEvents);
            na.add(e);
            return new Snapshot(sessions, turns, agentRuns, agentSteps, checkpoints, drafts,
                    snapshots, validationReports, tasks, approvals, backtestRuns, na);
        }
    }

    /** What {@link #readFile()} found + whether anything had to be skipped. */
    private record ReadResult(Snapshot snapshot, boolean repaired) {}

    private ValidationReport latestReport(Snapshot snap, String draftId) {
        return snap.validationReports().stream()
                .filter(r -> r.draftId().equals(draftId))
                .max(Comparator.comparing(ValidationReport::validatedAt))
                .orElse(null);
    }

    private ConversationSession sessionByIdOrThrow(Snapshot snap, String sessionId) {
        String wsId = currentWorkspaceId();
        for (ConversationSession s : snap.sessions()) {
            if (s.id().equals(sessionId) && s.workspaceId().equals(wsId)) return s;
        }
        // A session of ANOTHER workspace is deliberately "not found".
        throw TaskCenterApiException.sessionNotFound("当前工作空间不存在该会话：" + sessionId);
    }

    private StrategySpecDraft draftByIdOrThrow(Snapshot snap, String draftId) {
        String wsId = currentWorkspaceId();
        for (StrategySpecDraft d : snap.drafts()) {
            if (d.id().equals(draftId) && d.workspaceId().equals(wsId)) return d;
        }
        throw TaskCenterApiException.draftNotFound("当前工作空间不存在该草案：" + draftId);
    }

    private AgentTask taskByIdOrThrow(Snapshot snap, String taskId) {
        String wsId = currentWorkspaceId();
        for (AgentTask t : snap.tasks()) {
            if (t.id().equals(taskId) && t.workspaceId().equals(wsId)) return t;
        }
        throw TaskCenterApiException.taskNotFound("当前工作空间不存在该任务：" + taskId);
    }

    private AgentRun runByIdOrThrow(Snapshot snap, String runId) {
        String wsId = currentWorkspaceId();
        for (AgentRun r : snap.agentRuns()) {
            if (r.id().equals(runId) && r.workspaceId().equals(wsId)) return r;
        }
        throw TaskCenterApiException.runNotFound("当前工作空间不存在该 Agent 运行：" + runId);
    }

    private static int maxAttempt(Snapshot snap, String taskId) {
        return snap.backtestRuns().stream()
                .filter(r -> r.taskId().equals(taskId))
                .mapToInt(BacktestRun::attemptNumber)
                .max().orElse(0);
    }

    private AuditEvent audit(String workspaceId, String action, String entityType,
                             String entityId, String detail) {
        return new AuditEvent(UUID.randomUUID().toString(), workspaceId,
                currentMemberId(), action, entityType, entityId, detail,
                Instant.now().toString());
    }

    private static boolean allNull(TaskCenterDtos.DraftPatch p) {
        return p.name() == null && p.instruments() == null && p.timeframe() == null
                && p.fastWindow() == null && p.slowWindow() == null
                && p.positionSize() == null && p.stopLossPct() == null
                && p.feeBps() == null && p.slippageBps() == null
                && p.entryCondition() == null && p.exitCondition() == null
                && p.riskLimits() == null && p.backtestAssumptions() == null
                && p.templateType() == null && p.parameters() == null;
    }

    /**
     * Whether the crash-recovery pass (RUNNING → CANCELLED) has already run
     * for this store instance. Once the instance has started executing (and a
     * persisted RUNNING state can be a REAL in-flight attempt running outside
     * the store lock), later loads must NOT cancel RUNNING records — only a
     * fresh store instance (new process) repairs crash-left attempts.
     */
    private volatile boolean bootRunRepairDone = false;

    /**
     * Load the full state, recover any interrupted freeze transaction, then
     * restore the GLOBAL invariants deterministically. Rules:
     * <ul>
     *   <li>sessions / turns / drafts / agent runs / steps / checkpoints /
     *       validation reports / tasks whose workspace no longer exists or
     *       whose parent object was dropped are dropped (illegal
     *       associations);</li>
     *   <li>duplicate ids are resolved deterministically (earliest createdAt,
     *       tie-break by id) — except for audit events which are append-only;</li>
     *   <li>records with illegal fields / values / statuses are dropped;</li>
     *   <li>a task left in RUNNING (crashed mid-run) is repaired to CANCELLED
     *       (local cancellation, unrelated to trading) together with its
     *       QUEUED/RUNNING attempts;</li>
     *   <li>FROZEN spec snapshots and COMPLETED backtest results are NEVER
     *       modified or dropped (immutability) — even when orphaned;</li>
     *   <li>audit events are append-only: never dropped, never rewritten.</li>
     * </ul>
     * Whenever anything is dropped or normalized the cleaned whitelisted
     * snapshot is written back ATOMICALLY; the repair converges (a second load
     * never drifts). Logs carry only sanitised ids + the repair type — never
     * record content or credential-shaped fields. This method is pure
     * metadata work: it NEVER triggers an Agent run, a backtest, a diagnosis,
     * the probe or SimNow.
     */
    private Snapshot loadAndRepair() {
        recoverInterruptedFreeze();
        ReadResult read = readFile();
        Snapshot snap = read.snapshot();
        // Crash recovery (RUNNING → CANCELLED) runs ONLY on the first load of
        // this store instance: after startup, a persisted RUNNING state is a
        // REAL in-flight attempt (startTask executes the runner OUTSIDE the
        // store lock), so later loads — including concurrent reads and the
        // conditional merge in finishStart — must never cancel it. A fresh
        // store instance (new process after a crash/restart) deterministically
        // repairs any crash-left RUNNING attempt on its first load.
        boolean crashRepair = !bootRunRepairDone;
        boolean repaired = read.repaired();
        List<String> wsIds = workspaceStore.list().stream()
                .map(com.supertrader.demo.workspace.Workspace::id)
                .toList();

        // EVENT confirmation fields are authoritative only when backed by an
        // exact, independently persisted confirmation record.  Approvals are
        // therefore repaired before drafts so draft repair can verify them.
        List<ApprovalRecord> approvals = new ArrayList<>();
        Set<String> approvalIds = new HashSet<>();
        for (ApprovalRecord a : orderedApprovals(snap.approvals())) {
            if (!wsIds.contains(a.workspaceId()) || !isValidApproval(a)) {
                repaired = drop("approval", a.id(), repaired);
                continue;
            }
            if (!approvalIds.add(a.id())) {
                repaired = drop("duplicate approval id", a.id(), repaired);
                continue;
            }
            approvals.add(a);
        }

        // Pass 1: per-record legality (workspace + parent references + status).
        List<ConversationSession> sessions = new ArrayList<>();
        Set<String> sessionIds = new HashSet<>();
        for (ConversationSession s : orderedSessions(snap.sessions())) {
            if (!wsIds.contains(s.workspaceId()) || !isValidSession(s)) {
                repaired = drop("session", s.id(), repaired);
                continue;
            }
            if (!sessionIds.add(s.id())) {
                repaired = drop("duplicate session id", s.id(), repaired);
                continue;
            }
            sessions.add(s);
        }

        List<ConversationTurn> turns = new ArrayList<>();
        Set<String> turnIds = new HashSet<>();
        for (ConversationTurn t : orderedTurns(snap.turns())) {
            if (!sessionIds.contains(t.sessionId()) || !wsIds.contains(t.workspaceId())
                    || !isValidTurn(t)) {
                repaired = drop("turn", t.id(), repaired);
                continue;
            }
            if (!turnIds.add(t.id())) {
                repaired = drop("duplicate turn id", t.id(), repaired);
                continue;
            }
            turns.add(t);
        }

        List<AgentRun> agentRuns = new ArrayList<>();
        Set<String> runIds = new HashSet<>();
        for (AgentRun r : orderedAgentRuns(snap.agentRuns())) {
            if (!sessionIds.contains(r.sessionId()) || !wsIds.contains(r.workspaceId())
                    || !isValidAgentRun(r)) {
                repaired = drop("agentRun", r.id(), repaired);
                continue;
            }
            if (!runIds.add(r.id())) {
                repaired = drop("duplicate agentRun id", r.id(), repaired);
                continue;
            }
            agentRuns.add(r);
        }

        List<AgentStep> agentSteps = new ArrayList<>();
        Set<String> stepIds = new HashSet<>();
        for (AgentStep s : orderedSteps(snap.agentSteps())) {
            if (!runIds.contains(s.runId()) || !sessionIds.contains(s.sessionId())
                    || !wsIds.contains(s.workspaceId()) || !isValidAgentStep(s)) {
                repaired = drop("agentStep", s.id(), repaired);
                continue;
            }
            if (!stepIds.add(s.id())) {
                repaired = drop("duplicate agentStep id", s.id(), repaired);
                continue;
            }
            agentSteps.add(s);
        }

        List<RunCheckpoint> checkpoints = new ArrayList<>();
        Set<String> checkpointIds = new HashSet<>();
        for (RunCheckpoint c : orderedCheckpoints(snap.checkpoints())) {
            if (!runIds.contains(c.runId()) || !sessionIds.contains(c.sessionId())
                    || !wsIds.contains(c.workspaceId()) || !isValidCheckpoint(c)) {
                repaired = drop("checkpoint", c.id(), repaired);
                continue;
            }
            if (!checkpointIds.add(c.id())) {
                repaired = drop("duplicate checkpoint id", c.id(), repaired);
                continue;
            }
            checkpoints.add(c);
        }

        List<StrategySpecDraft> drafts = new ArrayList<>();
        Set<String> draftIds = new HashSet<>();
        for (StrategySpecDraft d : orderedDrafts(snap.drafts())) {
            if (!sessionIds.contains(d.sessionId()) || !wsIds.contains(d.workspaceId())
                    || !isValidDraft(d)) {
                repaired = drop("draft", d.id(), repaired);
                continue;
            }
            if (!draftIds.add(d.id())) {
                repaired = drop("duplicate draft id", d.id(), repaired);
                continue;
            }
            if (StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(d.templateType())
                    && d.parameters() != null && d.parameters().eventSignal() != null) {
                SpecParameters.EventSignal event = d.parameters().eventSignal();
                boolean hasClaimedConfirmation = event.confirmationId() != null
                        || event.confirmedByMemberId() != null || event.confirmedAt() != null;
                if (hasClaimedConfirmation
                        && !hasAuthoritativeEventConfirmation(d, approvals)) {
                    d = clearUntrustedEventConfirmation(d);
                    repaired = true;
                }
            }
            drafts.add(d);
        }

        // Snapshots are IMMUTABLE: never dropped, even when orphaned; only
        // duplicate ids are resolved (deterministic first).
        List<StrategySpecSnapshot> snapshots = new ArrayList<>();
        Set<String> snapshotIds = new HashSet<>();
        for (StrategySpecSnapshot s : orderedSnapshots(snap.snapshots())) {
            if (!isValidSnapshot(s)) {
                repaired = drop("snapshot", s.id(), repaired);
                continue;
            }
            if (!snapshotIds.add(s.id())) {
                repaired = drop("duplicate snapshot id", s.id(), repaired);
                continue;
            }
            snapshots.add(s);
        }

        List<ValidationReport> reports = new ArrayList<>();
        Set<String> reportIds = new HashSet<>();
        for (ValidationReport r : orderedReports(snap.validationReports())) {
            if (!draftIds.contains(r.draftId()) || !wsIds.contains(r.workspaceId())
                    || !isValidReport(r)) {
                repaired = drop("validationReport", r.id(), repaired);
                continue;
            }
            if (!reportIds.add(r.id())) {
                repaired = drop("duplicate validationReport id", r.id(), repaired);
                continue;
            }
            reports.add(r);
        }

        // Tasks: illegal associations are dropped unless the task is terminal
        // (completed tasks keep their runs visible); crashed RUNNING tasks are
        // repaired to CANCELLED.
        List<AgentTask> tasks = new ArrayList<>();
        Set<String> taskIds = new HashSet<>();
        for (AgentTask t : orderedTasks(snap.tasks())) {
            if (!sessionIds.contains(t.sessionId()) || !wsIds.contains(t.workspaceId())
                    || !isValidTask(t)) {
                repaired = drop("task", t.id(), repaired);
                continue;
            }
            String taskSnapshotId = t.snapshotId();
            boolean snapshotExists = snapshots.stream()
                    .anyMatch(s -> s.id().equals(taskSnapshotId));
            if (!snapshotExists && !isTerminalTask(t)) {
                repaired = drop("task with missing snapshot", t.id(), repaired);
                continue;
            }
            if (!taskIds.add(t.id())) {
                repaired = drop("duplicate task id", t.id(), repaired);
                continue;
            }
            if (crashRepair && AgentTask.STATUS_RUNNING.equals(t.status())) {
                t = new AgentTask(t.id(), t.workspaceId(), t.sessionId(), t.draftId(),
                        t.snapshotId(), t.strategyId(), t.versionNumber(), t.type(),
                        t.datasetId(), t.name(), AgentTask.STATUS_CANCELLED,
                        t.requestedByMemberId(), t.createdAt(), Instant.now().toString(),
                        Instant.now().toString(), "服务重启中断（本地任务取消，与交易无关）",
                        t.attemptCount());
                repaired = true;
            }
            tasks.add(t);
        }

        // Approvals were repaired before drafts because EVENT drafts depend on
        // their exact confirmation provenance. They remain append-only here.

        // Backtest runs: COMPLETED results are never modified or dropped; a
        // RUNNING attempt of a crashed run (server restart) is repaired to
        // CANCELLED; QUEUED attempts stay QUEUED (they wait for the manual
        // start click).
        List<BacktestRun> backtestRuns = new ArrayList<>();
        Set<String> runIds2 = new HashSet<>();
        for (BacktestRun r : orderedBacktestRuns(snap.backtestRuns())) {
            if (!wsIds.contains(r.workspaceId())
                    || !isValidBacktestRun(r, tasks, snapshots)) {
                repaired = drop("backtestRun", r.id(), repaired);
                continue;
            }
            if (!runIds2.add(r.id())) {
                repaired = drop("duplicate backtestRun id", r.id(), repaired);
                continue;
            }
            if (crashRepair && BacktestRun.STATUS_RUNNING.equals(r.status())) {
                r = new BacktestRun(r.id(), r.taskId(), r.workspaceId(), r.attemptNumber(),
                        BacktestRun.STATUS_CANCELLED, r.datasetId(), r.instrument(),
                        r.timeframe(), r.periodStart(), r.periodEnd(), r.inputBars(),
                        r.trades(), r.grossReturnPct(), r.netReturnPct(), r.maxDrawdownPct(),
                        r.winRate(), r.feeAssumptionBps(), r.slippageAssumptionBps(),
                        r.sampleOutStatus(), r.durationMs(), BacktestRunnerService.ERROR_CANCELLED,
                        "服务重启中断（本地任务取消，与交易无关）", r.startedAt(),
                        Instant.now().toString());
                repaired = true;
            }
            backtestRuns.add(r);
        }

        // Audit events: APPEND-ONLY — never dropped, never rewritten, only
        // duplicate ids are resolved deterministically.
        List<AuditEvent> audits = new ArrayList<>();
        Set<String> auditIds = new HashSet<>();
        for (AuditEvent a : orderedAudits(snap.auditEvents())) {
            if (!isValidAudit(a)) {
                repaired = drop("auditEvent", a.id(), repaired);
                continue;
            }
            if (!auditIds.add(a.id())) {
                repaired = drop("duplicate auditEvent id", a.id(), repaired);
                continue;
            }
            audits.add(a);
        }

        Snapshot result = new Snapshot(sessions, turns, agentRuns, agentSteps,
                checkpoints, drafts, snapshots, reports, tasks, approvals,
                backtestRuns, audits);
        if (repaired) persist(result);
        bootRunRepairDone = true;
        return result;
    }

    private boolean drop(String reason, String id, boolean repaired) {
        log.warn("Repair: dropped {} (id=?)", reason);
        return true;
    }

    // Deterministic ordering used by every dedupe pass (createdAt / decidedAt /
    // validatedAt / frozenAt / startedAt first, then id as the stable tie-break).
    private static List<ConversationSession> orderedSessions(List<ConversationSession> in) {
        return in.stream()
                .sorted(Comparator.comparing(ConversationSession::createdAt)
                        .thenComparing(ConversationSession::id))
                .toList();
    }

    private static List<ConversationTurn> orderedTurns(List<ConversationTurn> in) {
        return in.stream()
                .sorted(Comparator.comparing(ConversationTurn::createdAt)
                        .thenComparing(ConversationTurn::id))
                .toList();
    }

    private static List<AgentRun> orderedAgentRuns(List<AgentRun> in) {
        return in.stream()
                .sorted(Comparator.comparing(AgentRun::createdAt)
                        .thenComparing(AgentRun::id))
                .toList();
    }

    private static List<AgentStep> orderedSteps(List<AgentStep> in) {
        return in.stream()
                .sorted(Comparator.comparing(AgentStep::createdAt)
                        .thenComparing(AgentStep::id))
                .toList();
    }

    private static List<RunCheckpoint> orderedCheckpoints(List<RunCheckpoint> in) {
        return in.stream()
                .sorted(Comparator.comparing(RunCheckpoint::createdAt)
                        .thenComparing(RunCheckpoint::id))
                .toList();
    }

    private static List<StrategySpecDraft> orderedDrafts(List<StrategySpecDraft> in) {
        return in.stream()
                .sorted(Comparator.comparing(StrategySpecDraft::createdAt)
                        .thenComparing(StrategySpecDraft::id))
                .toList();
    }

    private static List<StrategySpecSnapshot> orderedSnapshots(List<StrategySpecSnapshot> in) {
        return in.stream()
                .sorted(Comparator.comparing(StrategySpecSnapshot::frozenAt)
                        .thenComparing(StrategySpecSnapshot::id))
                .toList();
    }

    private static List<ValidationReport> orderedReports(List<ValidationReport> in) {
        return in.stream()
                .sorted(Comparator.comparing(ValidationReport::validatedAt)
                        .thenComparing(ValidationReport::id))
                .toList();
    }

    private static List<AgentTask> orderedTasks(List<AgentTask> in) {
        return in.stream()
                .sorted(Comparator.comparing(AgentTask::createdAt)
                        .thenComparing(AgentTask::id))
                .toList();
    }

    private static List<ApprovalRecord> orderedApprovals(List<ApprovalRecord> in) {
        return in.stream()
                .sorted(Comparator.comparing(ApprovalRecord::decidedAt)
                        .thenComparing(ApprovalRecord::id))
                .toList();
    }

    private static List<BacktestRun> orderedBacktestRuns(List<BacktestRun> in) {
        return in.stream()
                .sorted(Comparator.comparing(BacktestRun::startedAt)
                        .thenComparing(BacktestRun::id))
                .toList();
    }

    private static List<AuditEvent> orderedAudits(List<AuditEvent> in) {
        return in.stream()
                .sorted(Comparator.comparing(AuditEvent::createdAt)
                        .thenComparing(AuditEvent::id))
                .toList();
    }

    private static boolean isTerminalTask(AgentTask t) {
        return AgentTask.STATUS_SUCCEEDED.equals(t.status())
                || AgentTask.STATUS_FAILED.equals(t.status())
                || AgentTask.STATUS_CANCELLED.equals(t.status());
    }

    private static boolean isTerminalRun(BacktestRun r) {
        return BacktestRun.STATUS_SUCCEEDED.equals(r.status())
                || BacktestRun.STATUS_FAILED.equals(r.status())
                || BacktestRun.STATUS_CANCELLED.equals(r.status());
    }

    /**
     * Read the file and validate the TOP-LEVEL protocol strictly: the root
     * MUST be a JSON object; the ONLY allowed top-level fields are the twelve
     * arrays of {@value #SCHEMA}; {@code schema} MUST exist, be a string and
     * equal {@value #SCHEMA} exactly; every array MUST exist and be an array.
     * Any violation enters the deterministic repair flow: still-readable
     * arrays keep their records, unknown top-level fields are dropped, the
     * schema is normalised, and a missing / non-array array safely degrades to
     * an empty list. No record content or unknown-field name is ever logged.
     */
    private ReadResult readFile() {
        if (!Files.exists(file)) {
            return new ReadResult(new Snapshot(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of()), false);
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new ReadResult(new Snapshot(List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()), false);
            }
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                log.warn("Repair: task-center file root is not an object, will repair");
                return new ReadResult(emptySnapshot(), true);
            }
            boolean topLevelRepair = false;
            java.util.Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                if (!"schema".equals(name) && !ARRAY_FIELDS.contains(name)) {
                    log.warn("Repair: dropped an unknown top-level field in task-center file");
                    topLevelRepair = true;
                }
            }
            JsonNode schemaNode = root.path("schema");
            if (!schemaNode.isTextual() || !SCHEMA.equals(schemaNode.asText())) {
                log.warn("Repair: task-center file schema missing/wrong, will normalise");
                topLevelRepair = true;
            }

            Snapshot partial = new Snapshot(
                    readArray(root, "sessions", ConversationSession.class),
                    readArray(root, "turns", ConversationTurn.class),
                    readArray(root, "agentRuns", AgentRun.class),
                    readArray(root, "agentSteps", AgentStep.class),
                    readArray(root, "checkpoints", RunCheckpoint.class),
                    readArray(root, "drafts", StrategySpecDraft.class),
                    readArray(root, "specSnapshots", StrategySpecSnapshot.class),
                    readArray(root, "validationReports", ValidationReport.class),
                    readArray(root, "tasks", AgentTask.class),
                    readArray(root, "approvals", ApprovalRecord.class),
                    readArray(root, "backtestRuns", BacktestRun.class),
                    readArray(root, "auditEvents", AuditEvent.class));
            boolean arraysBroken = false;
            for (String name : ARRAY_FIELDS) {
                if (!root.path(name).isArray()) arraysBroken = true;
            }
            if (arraysBroken) {
                // A missing / non-array array safely degrades to an empty list
                // (the global repair then writes a legal snapshot back).
                log.warn("Repair: task-center file arrays missing/not arrays, will repair");
                return new ReadResult(emptySnapshot(), true);
            }
            return new ReadResult(partial, topLevelRepair);
        } catch (Exception e) {
            log.warn("Repair: task-center file unreadable, will repair to empty");
            return new ReadResult(emptySnapshot(), true);
        }
    }

    private static final List<String> ARRAY_FIELDS = List.of(
            "sessions", "turns", "agentRuns", "agentSteps", "checkpoints", "drafts",
            "specSnapshots", "validationReports", "tasks", "approvals", "backtestRuns",
            "auditEvents");

    private Snapshot emptySnapshot() {
        return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private Snapshot readFileSnapshot() {
        return readFile().snapshot();
    }

    private <T> List<T> readArray(JsonNode root, String name, Class<T> type) {
        List<T> out = new ArrayList<>();
        JsonNode arr = root.path(name);
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            try {
                out.add(mapper.treeToValue(n, type));
            } catch (Exception skip) {
                log.warn("Repair: dropped an unreadable entry in {}", name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ //
    // Per-record validity (strict whitelists)
    // ------------------------------------------------------------------ //

    private static boolean isValidSession(ConversationSession s) {
        return nonBlank(s.id(), s.workspaceId(), s.title(), s.createdByMemberId(),
                s.createdAt(), s.updatedAt())
                && ConversationSession.STATUS_ACTIVE.equals(s.status())
                && isValidInstant(s.createdAt()) && isValidInstant(s.updatedAt());
    }

    private static boolean isValidTurn(ConversationTurn t) {
        if (!nonBlank(t.id(), t.sessionId(), t.workspaceId(), t.content(), t.createdAt())) {
            return false;
        }
        if (!ConversationTurn.ROLE_USER.equals(t.role())
                && !ConversationTurn.ROLE_ASSISTANT.equals(t.role())) {
            return false;
        }
        if (!isValidIntent(t.intent())) return false;
        return isValidInstant(t.createdAt());
    }

    private static boolean isValidIntent(String intent) {
        return "GENERAL_QA".equals(intent) || "RESEARCH".equals(intent)
                || "STRATEGY_HYPOTHESIS".equals(intent) || "STRATEGY_CANDIDATE".equals(intent)
                || "STRATEGY_REFINEMENT".equals(intent) || "TASK_REQUEST".equals(intent);
    }

    private static boolean isValidAgentRun(AgentRun r) {
        return nonBlank(r.id(), r.sessionId(), r.workspaceId(), r.intent(), r.createdAt())
                && isValidRunStatus(r.status())
                && (r.budget() == null || r.budget().maxSteps() > 0)
                && isValidInstant(r.createdAt());
    }

    private static boolean isValidRunStatus(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status)
                || "COMPLETED".equals(status) || "FAILED".equals(status)
                || "CHECKPOINTED".equals(status) || "CANCELLED".equals(status);
    }

    private static boolean isValidAgentStep(AgentStep s) {
        return nonBlank(s.id(), s.runId(), s.sessionId(), s.workspaceId(), s.kind(), s.createdAt())
                && s.seq() > 0 && isValidInstant(s.createdAt());
    }

    private static boolean isValidCheckpoint(RunCheckpoint c) {
        return nonBlank(c.id(), c.runId(), c.sessionId(), c.workspaceId(), c.reason(),
                c.createdAt()) && isValidInstant(c.createdAt());
    }

    private static boolean isValidDraft(StrategySpecDraft d) {
        if (!nonBlank(d.id(), d.workspaceId(), d.sessionId(), d.createdByMemberId(),
                d.createdAt(), d.updatedAt())) {
            return false;
        }
        if (!isValidDraftStatus(d.status())) return false;
        if (!StrategySpecDraft.TEMPLATE_WHITELIST.contains(d.templateType())) return false;
        if (d.completeness() < 0 || d.completeness() > 100) return false;
        if (d.parameters() == null || !d.templateType().equals(d.parameters().type())) return false;
        return isValidInstant(d.createdAt()) && isValidInstant(d.updatedAt());
    }

    private static boolean isValidDraftStatus(String status) {
        return "CO_CREATING".equals(status) || "DRAFT_READY".equals(status)
                || "VALIDATION_FAILED".equals(status) || "PENDING_APPROVAL".equals(status)
                || "APPROVED".equals(status) || "REJECTED".equals(status)
                || "FROZEN".equals(status);
    }

    private static boolean isValidSnapshot(StrategySpecSnapshot s) {
        boolean base = nonBlank(s.id(), s.draftId(), s.workspaceId(), s.strategyId(), s.name(),
                s.templateType(), s.contentHash(), s.frozenAt(), s.frozenByMemberId())
                && s.versionNumber() >= 1
                && StrategySpecDraft.TEMPLATE_WHITELIST.contains(s.templateType())
                && s.parameters() != null && s.templateType().equals(s.parameters().type())
                && isValidInstant(s.frozenAt());
        if (!base) return false;
        if (s.parameters().schemaVersion() < SpecParameters.CURRENT_SCHEMA_VERSION) return true;
        String expected = StrategySpecCanonicalizer.hash(s.name(), s.templateType(),
                s.instruments(), s.timeframe(), s.parameters(), s.entryCondition(),
                s.exitCondition(), s.riskLimits(), s.backtestAssumptions());
        return expected.equals(s.contentHash());
    }

    private static boolean isValidReport(ValidationReport r) {
        return nonBlank(r.id(), r.draftId(), r.workspaceId(), r.validatedAt(),
                r.validatorVersion()) && isValidInstant(r.validatedAt());
    }

    private static boolean isValidTask(AgentTask t) {
        return nonBlank(t.id(), t.workspaceId(), t.sessionId(), t.draftId(), t.snapshotId(),
                t.strategyId(), t.type(), t.datasetId(), t.name(), t.requestedByMemberId(),
                t.createdAt(), t.updatedAt())
                && AgentTask.TYPE_BACKTEST.equals(t.type())
                && isValidTaskStatus(t.status())
                && t.versionNumber() >= 1
                && isValidInstant(t.createdAt()) && isValidInstant(t.updatedAt());
    }

    private static boolean isValidTaskStatus(String status) {
        return "PENDING_APPROVAL".equals(status) || "APPROVED".equals(status)
                || "REJECTED".equals(status) || "QUEUED".equals(status)
                || "RUNNING".equals(status) || "SUCCEEDED".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private static boolean isValidApproval(ApprovalRecord a) {
        boolean draftOrTaskDecision = (ApprovalRecord.ENTITY_DRAFT.equals(a.entityType())
                || ApprovalRecord.ENTITY_TASK.equals(a.entityType()))
                && (ApprovalRecord.DECISION_APPROVED.equals(a.decision())
                || ApprovalRecord.DECISION_REJECTED.equals(a.decision()));
        boolean eventDecision = ApprovalRecord.ENTITY_EVENT_EVIDENCE.equals(a.entityType())
                && ApprovalRecord.DECISION_CONFIRMED.equals(a.decision());
        return nonBlank(a.id(), a.workspaceId(), a.entityType(), a.entityId(),
                a.decision(), a.decidedByMemberId(), a.decidedAt())
                && (draftOrTaskDecision || eventDecision)
                && isValidInstant(a.decidedAt());
    }

    private boolean isValidBacktestRun(BacktestRun r, List<AgentTask> tasks,
                                       List<StrategySpecSnapshot> snapshots) {
        if (!nonBlank(r.id(), r.taskId(), r.workspaceId(), r.datasetId())) {
            return false;
        }
        if (!isValidRunStatus2(r.status())) return false;
        if (r.attemptNumber() < 1) return false;
        if (isTerminalRun(r)) {
            // Completed results must carry their deterministic metrics.
            if (r.finishedAt() == null || !isValidInstant(r.finishedAt())) return false;
            boolean completedResult = BacktestRun.STATUS_SUCCEEDED.equals(r.status())
                    || BacktestRun.STATUS_FAILED.equals(r.status());
            boolean hasNewProvenance = completedResult && (r.schemaVersion() != null
                    || r.templateType() != null || r.snapshotContentHash() != null
                    || r.datasetContentHash() != null || r.executionTiming() != null
                    || r.orders() != null);
            if (hasNewProvenance
                    && !java.util.Objects.equals(r.schemaVersion(),
                    BacktestRun.CURRENT_SCHEMA_VERSION)) return false;
            if (BacktestRun.STATUS_SUCCEEDED.equals(r.status())) {
                if (r.inputBars() < 1 || r.trades() < 0
                        || r.grossReturnPct() == null || r.netReturnPct() == null
                        || r.maxDrawdownPct() == null || r.winRate() == null
                        || r.feeAssumptionBps() == null || r.slippageAssumptionBps() == null
                        || r.periodStart() == null || r.periodEnd() == null) {
                    return false;
                }
                if (hasNewProvenance
                        && !isValidVersionedSucceededRun(r, tasks, snapshots)) return false;
            } else if (hasNewProvenance
                    && !isValidVersionedRunBinding(r, tasks, snapshots)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidVersionedSucceededRun(BacktestRun r, List<AgentTask> tasks,
                                                 List<StrategySpecSnapshot> snapshots) {
        if (!java.util.Objects.equals(r.schemaVersion(), BacktestRun.CURRENT_SCHEMA_VERSION)
                || r.templateType() == null
                || !StrategySpecDraft.TEMPLATE_WHITELIST.contains(r.templateType())
                || r.snapshotContentHash() == null
                || !r.snapshotContentHash().matches("[0-9a-f]{64}")
                || r.datasetContentHash() == null
                || !r.datasetContentHash().matches("sha256:[0-9a-f]{64}")
                || r.orders() == null
                || !Double.isFinite(r.grossReturnPct())
                || !Double.isFinite(r.netReturnPct())
                || !Double.isFinite(r.maxDrawdownPct())
                || !Double.isFinite(r.winRate())) {
            return false;
        }
        AgentTask task = tasks.stream()
                .filter(t -> t.id().equals(r.taskId())
                        && t.workspaceId().equals(r.workspaceId()))
                .findFirst().orElse(null);
        if (task == null || !task.datasetId().equals(r.datasetId())) return false;
        StrategySpecSnapshot snapshot = snapshots.stream()
                .filter(s -> s.id().equals(task.snapshotId())
                        && s.workspaceId().equals(task.workspaceId()))
                .findFirst().orElse(null);
        DatasetCatalog.Dataset dataset = catalog.load(task.datasetId());
        if (snapshot == null || dataset == null
                || !snapshot.templateType().equals(r.templateType())
                || !snapshot.contentHash().equals(r.snapshotContentHash())
                || !dataset.contentHash().equals(r.datasetContentHash())
                || !dataset.instrument().equals(r.instrument())
                || !dataset.timeframe().equals(r.timeframe())
                || !dataset.periodStart().equals(r.periodStart())
                || !dataset.periodEnd().equals(r.periodEnd())
                || dataset.bars().size() != r.inputBars()
                || !dataset.sampleOutStatus().equals(r.sampleOutStatus())
                || snapshot.parameters() == null
                || !java.util.Objects.equals(snapshot.parameters().feeBps(),
                r.feeAssumptionBps())
                || !java.util.Objects.equals(snapshot.parameters().slippageBps(),
                r.slippageAssumptionBps())) return false;
        String expectedTiming = StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(r.templateType())
                ? "EVENT_AVAILABLE->NEXT_BAR_OPEN" : "BAR_CLOSE->NEXT_BAR_OPEN";
        if (!expectedTiming.equals(r.executionTiming())) return false;
        int exits = 0;
        boolean inPosition = false;
        String expectedSignalAt = StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(r.templateType())
                ? SpecParameters.SIGNAL_EVENT_AVAILABLE : SpecParameters.SIGNAL_BAR_CLOSE;
        for (BacktestRunnerService.OrderFill order : r.orders()) {
            if (order == null || !nonBlank(order.action(), order.direction(), order.signalAt(),
                    order.fillAt(), order.signalBarTs(), order.fillBarTs(), order.reason())
                    || !("ENTRY".equals(order.action()) || "EXIT".equals(order.action()))
                    || !("LONG".equals(order.direction()) || "SHORT".equals(order.direction()))
                    || !SpecParameters.FILL_NEXT_BAR_OPEN.equals(order.fillAt())
                    || !isValidInstant(order.signalBarTs()) || !isValidInstant(order.fillBarTs())
                    || !Instant.parse(order.fillBarTs()).isAfter(Instant.parse(order.signalBarTs()))
                    || !Double.isFinite(order.rawPrice()) || order.rawPrice() <= 0
                    || !Double.isFinite(order.price()) || order.price() <= 0
                    || !Double.isFinite(order.fee()) || order.fee() < 0
                    || !Double.isFinite(order.slippage()) || order.slippage() < 0) {
                return false;
            }
            if (!expectedSignalAt.equals(order.signalAt())) return false;
            if ("ENTRY".equals(order.action())) {
                if (inPosition) return false;
                inPosition = true;
            } else {
                if (!inPosition) return false;
                inPosition = false;
                exits++;
            }
            int signalIndex = -1;
            for (int i = 0; i < dataset.bars().size(); i++) {
                if (dataset.bars().get(i).ts().equals(order.signalBarTs())) {
                    signalIndex = i;
                    break;
                }
            }
            if (signalIndex < 0 || signalIndex + 1 >= dataset.bars().size()
                    || !dataset.bars().get(signalIndex + 1).ts().equals(order.fillBarTs())
                    || Double.compare(dataset.bars().get(signalIndex + 1).open(),
                    order.rawPrice()) != 0) return false;
        }
        return exits == r.trades() && r.winRate() >= 0 && r.winRate() <= 100
                && r.maxDrawdownPct() >= 0
                && r.netReturnPct() <= r.grossReturnPct() + 0.0000001;
    }

    private boolean isValidVersionedRunBinding(BacktestRun r, List<AgentTask> tasks,
                                               List<StrategySpecSnapshot> snapshots) {
        AgentTask task = tasks.stream()
                .filter(t -> t.id().equals(r.taskId())
                        && t.workspaceId().equals(r.workspaceId()))
                .findFirst().orElse(null);
        if (task == null || !task.datasetId().equals(r.datasetId())) return false;
        StrategySpecSnapshot snapshot = snapshots.stream()
                .filter(s -> s.id().equals(task.snapshotId())
                        && s.workspaceId().equals(task.workspaceId()))
                .findFirst().orElse(null);
        DatasetCatalog.Dataset dataset = catalog.load(task.datasetId());
        String expectedTiming = snapshot != null
                && StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(snapshot.templateType())
                ? "EVENT_AVAILABLE->NEXT_BAR_OPEN" : "BAR_CLOSE->NEXT_BAR_OPEN";
        return snapshot != null && snapshot.templateType().equals(r.templateType())
                && snapshot.contentHash().equals(r.snapshotContentHash())
                && expectedTiming.equals(r.executionTiming())
                && r.orders() != null
                && (dataset == null ? r.datasetContentHash() == null
                : dataset.contentHash().equals(r.datasetContentHash()));
    }

    private static boolean isValidRunStatus2(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status)
                || "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status);
    }

    private static boolean isValidAudit(AuditEvent a) {
        return nonBlank(a.id(), a.workspaceId(), a.actorMemberId(), a.action(),
                a.entityType(), a.entityId(), a.detail(), a.createdAt())
                && isValidInstant(a.createdAt());
    }

    private static boolean nonBlank(String... values) {
        for (String v : values) {
            if (v == null || v.isBlank()) return false;
        }
        return true;
    }

    private static boolean isValidInstant(String iso) {
        if (iso == null || iso.isBlank()) return false;
        try {
            Instant.parse(iso);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Atomic-ish write: temp file then move (fallback to non-atomic move). */
    private void persist(Snapshot snapshot) {
        persistWithHook(snapshot);
    }

    private void persistWithHook(Snapshot snapshot) {
        Runnable hook = persistHook;
        if (hook != null) hook.run();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(),
                    new TaskCenterFile(SCHEMA, snapshot.sessions(), snapshot.turns(),
                            snapshot.agentRuns(), snapshot.agentSteps(),
                            snapshot.checkpoints(), snapshot.drafts(), snapshot.snapshots(),
                            snapshot.validationReports(), snapshot.tasks(),
                            snapshot.approvals(), snapshot.backtestRuns(),
                            snapshot.auditEvents()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new TaskCenterApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    TaskCenterApiException.CODE_PERSIST_FAILED,
                    "任务中心保存失败：" + e.getMessage());
        }
    }

    /** The on-disk envelope (strict top-level protocol). */
    record TaskCenterFile(
            @JsonProperty("schema") String schema,
            @JsonProperty("sessions") List<ConversationSession> sessions,
            @JsonProperty("turns") List<ConversationTurn> turns,
            @JsonProperty("agentRuns") List<AgentRun> agentRuns,
            @JsonProperty("agentSteps") List<AgentStep> agentSteps,
            @JsonProperty("checkpoints") List<RunCheckpoint> checkpoints,
            @JsonProperty("drafts") List<StrategySpecDraft> drafts,
            @JsonProperty("specSnapshots") List<StrategySpecSnapshot> specSnapshots,
            @JsonProperty("validationReports") List<ValidationReport> validationReports,
            @JsonProperty("tasks") List<AgentTask> tasks,
            @JsonProperty("approvals") List<ApprovalRecord> approvals,
            @JsonProperty("backtestRuns") List<BacktestRun> backtestRuns,
            @JsonProperty("auditEvents") List<AuditEvent> auditEvents) {
        @JsonCreator
        TaskCenterFile {}
    }

    // ================================================================== //
    // Detail records returned to the service / controller
    // ================================================================== //

    public record SessionDetail(ConversationSession session, List<ConversationTurn> turns,
                                List<StrategySpecDraft> drafts, List<AgentTask> tasks) {}

    public record DraftDetail(StrategySpecDraft draft, ValidationReport latestReport,
                              StrategySpecSnapshot snapshot, List<ApprovalRecord> approvals) {}

    public record TaskDetail(AgentTask task, List<ApprovalRecord> approvals,
                             List<BacktestRun> runs) {}

    public record AgentRunDetail(AgentRun run, List<AgentStep> steps,
                                 RunCheckpoint checkpoint) {}

    public record TaskAudit(List<ApprovalRecord> approvals, List<AuditEvent> auditEvents) {}

    public record SeedDecisionResult(StrategySeed seed, StrategySpecDraft draft) {}

    public record ValidationResponse(StrategySpecDraft draft, ValidationReport report) {}

    public record TaskStartResult(AgentTask task, BacktestRun run) {}
}
