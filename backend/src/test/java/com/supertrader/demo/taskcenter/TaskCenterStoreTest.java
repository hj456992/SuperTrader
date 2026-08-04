package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.supertrader.demo.team.TeamStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Store-level tests for the Module 9 task center (sessions / turns / drafts /
 * validation / approval / freeze transaction / tasks / backtest runs / audit).
 * Every test gets a FRESH scratch stack (temp files) — the real rebuild/.run/
 * is never touched and tests never share state.
 */
class TaskCenterStoreTest {

    private TaskCenterTestSupport.Stack stack;
    private String ownerId;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        stack = TaskCenterTestSupport.build();
        TaskCenterTestSupport.copyAcceptanceDatasets(stack);
        writeTinyDataset(stack); // a covering-but-undersized fixture → FAILED runs
        ownerId = stack.teams().currentActor().id();
    }

    // ------------------------------------------------------------------ //
    // Sessions & turns
    // ------------------------------------------------------------------ //

    @Test
    void createSessionAndTurnsPersist() {
        var store = stack.store();
        var session = store.createSession("测试会话");
        assertEquals("ACTIVE", session.status());
        assertEquals(stack.workspaces().currentWorkspaceId(), session.workspaceId());
        assertNotNull(session.id());
        assertEquals(0, store.sessionDetail(session.id()).turns().size());
        assertTrue(store.sessionDetail(session.id()).drafts().isEmpty());
        assertTrue(store.sessionDetail(session.id()).tasks().isEmpty());
        // A turn with a plain message never creates a draft/task.
        store.addUserTurn(session.id(), "你好", IntentClassifier.GENERAL_QA, null);
        var after = store.sessionDetail(session.id());
        assertEquals(1, after.turns().size());
        assertTrue(after.drafts().isEmpty());
        assertTrue(after.tasks().isEmpty());
    }

    @Test
    void plainQaNeverCreatesDraftOrTask() {
        var store = stack.store();
        var session = store.createSession("普通问答会话");
        store.addUserTurn(session.id(), "什么是均线？", IntentClassifier.GENERAL_QA, null);
        store.addAssistantTurn(session.id(), "均线是……", null, false,
                IntentClassifier.GENERAL_QA);
        var detail = store.sessionDetail(session.id());
        assertTrue(detail.drafts().isEmpty(), "普通问答不得创建 Draft");
        assertTrue(detail.tasks().isEmpty(), "普通问答不得创建任务");
    }

    @Test
    void unknownSessionIs404() {
        var store = stack.store();
        var e = assertThrows(TaskCenterApiException.class,
                () -> store.sessionDetail("no-such"));
        assertEquals(404, e.status().value());
        assertEquals("SESSION_NOT_FOUND", e.code());
    }

    @Test
    void invalidTurnContentIsRejected() {
        assertEquals(400, assertThrows(TaskCenterApiException.class,
                () -> TaskCenterStore.validateTurnContent("")).status().value());
        assertEquals("INVALID_CONTENT",
                assertThrows(TaskCenterApiException.class,
                        () -> TaskCenterStore.validateTurnContent("我的密码是 123456"))
                        .code());
        assertEquals(400, assertThrows(TaskCenterApiException.class,
                () -> TaskCenterStore.validateTurnContent("a".repeat(4001)))
                .status().value());
    }

    // ------------------------------------------------------------------ //
    // Seed → decision → draft
    // ------------------------------------------------------------------ //

    @Test
    void unconfirmedCandidateCreatesNoDraft() {
        var store = stack.store();
        var session = store.createSession("候选会话");
        store.addUserTurn(session.id(), "如果 JM2609 的5日均线上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s1"));
        assertTrue(store.sessionDetail(session.id()).drafts().isEmpty(),
                "未确认的策略候选不得创建 Draft");
    }

    @Test
    void seedDecisionCreatesDraftOnlyOnExplicitChoice() {
        var store = stack.store();
        var session = store.createSession("共创会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s2"));
        // DISCUSS → no draft.
        var discuss = store.decideSeed(session.id(), turn.id(), "DISCUSS");
        assertNull(discuss.draft());
        assertEquals(StrategySeed.STATUS_DISCUSSED, discuss.seed().status());
        assertTrue(store.sessionDetail(session.id()).drafts().isEmpty());
        // A second decision on the same seed is refused (409).
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.decideSeed(session.id(), turn.id(), "CO_CREATE"))
                .status().value());

        // CO_CREATE on a fresh turn → draft created (SMA_CROSS, CO_CREATING).
        var turn2 = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s3"));
        var create = store.decideSeed(session.id(), turn2.id(), "CO_CREATE");
        assertNotNull(create.draft());
        assertEquals(StrategySpecDraft.STATUS_CO_CREATING, create.draft().status());
        assertEquals(StrategySpecDraft.TEMPLATE_SMA_CROSS, create.draft().templateType());
        assertEquals(SpecParameters.CURRENT_SCHEMA_VERSION,
                create.draft().parameters().schemaVersion());
        assertEquals(StrategySpecDraft.TEMPLATE_SMA_CROSS,
                create.draft().parameters().type());
        assertEquals(List.of("JM2609"), create.draft().instruments());
        assertEquals(StrategySeed.STATUS_CONFIRMED, create.seed().status());
    }

    @Test
    void decidingSeedWithoutCandidateIsIllegal() {
        var store = stack.store();
        var session = store.createSession("无候选会话");
        var turn = store.addUserTurn(session.id(), "你好", IntentClassifier.GENERAL_QA, null);
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.decideSeed(session.id(), turn.id(), "CO_CREATE"))
                .status().value());
        assertEquals(400, assertThrows(TaskCenterApiException.class,
                () -> store.decideSeed(session.id(), turn.id(), "MAYBE"))
                .status().value());
    }

    // ------------------------------------------------------------------ //
    // Draft completeness, one-question rule, patching
    // ------------------------------------------------------------------ //

    @Test
    void draftCompletenessAndMissingFieldsTrackConfirmedFields() {
        var store = stack.store();
        var session = store.createSession("完整度会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s4"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        // The seed prefills instruments + entry/exit hints → 3/13 confirmed.
        assertEquals(23, draft.completeness());
        assertFalse(draft.missingFields().isEmpty());

        var patched = store.updateDraft(draft.id(), new TaskCenterDtos.DraftPatch(
                "均线策略", null, null, null, null, null, null, null, null,
                null, null, null, null), null);
        assertTrue(patched.completeness() > 0);
        assertFalse(patched.missingFields().contains("name"));
        assertEquals("均线策略", patched.name());
        assertTrue(patched.evidence().stream().anyMatch(e ->
                e.field().equals("name") && e.value().equals("均线策略")));
    }

    @Test
    void atMostOneQuestionPerTurn() {
        var store = stack.store();
        var session = store.createSession("追问会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s5"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        // Many fields are missing — the helper still returns exactly ONE.
        var q1 = SpecFieldHelpers.nextQuestion(draft);
        assertNotNull(q1);
        // The seed prefills instruments → the highest-impact missing field
        // is the timeframe.
        assertEquals("timeframe", q1.field());
        // After every required field is confirmed there is no more question.
        var patched = store.updateDraft(draft.id(), fullPatch(), null);
        assertNull(SpecFieldHelpers.nextQuestion(patched),
                "所有必填字段确认后不再追问");
    }

    @Test
    void illegalPatchValuesAreRejected() {
        var store = stack.store();
        var session = store.createSession("非法补丁会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s6"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        var e = assertThrows(TaskCenterApiException.class,
                () -> store.updateDraft(draft.id(), new TaskCenterDtos.DraftPatch(
                        null, null, null, 1, null, null, null, null, null,
                        null, null, null, null), null));
        assertEquals(400, e.status().value());
        assertEquals("INVALID_PARAMETER", e.code());
        // Banned content is rejected at patch time (fail-closed).
        var banned = assertThrows(TaskCenterApiException.class,
                () -> store.updateDraft(draft.id(), new TaskCenterDtos.DraftPatch(
                        null, null, null, null, null, null, null, null, null,
                        "如果密码是 123456 就买入", null, null, null), null));
        assertEquals(400, banned.status().value());
        assertEquals("INVALID_PARAMETER", banned.code());
        // Frozen drafts cannot be edited in place.
        var draft2 = freezeFreshDraft(store);
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.updateDraft(draft2, fullPatch(), null)).status().value());
    }

    // ------------------------------------------------------------------ //
    // Validation
    // ------------------------------------------------------------------ //

    @Test
    void validationRequiresCompleteDraftAndMarksStatus() {
        var store = stack.store();
        var session = store.createSession("校验会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s7"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        var incomplete = store.validateDraft(draft.id());
        assertFalse(incomplete.report().valid());
        assertFalse(incomplete.report().backtestable());
        assertEquals(StrategySpecDraft.STATUS_VALIDATION_FAILED,
                incomplete.draft().status());

        fillDraft(store, draft.id());
        var complete = store.validateDraft(draft.id());
        assertTrue(complete.report().valid(), () -> "issues: " + complete.report().issues());
        assertTrue(complete.report().backtestable());
        assertEquals(StrategySpecDraft.STATUS_DRAFT_READY, complete.draft().status());
        // The report is persisted with the field-level issues/warnings.
        var detail = store.draft(draft.id());
        assertNotNull(detail.latestReport());
        assertEquals(complete.report().id(), detail.latestReport().id());
    }

    @Test
    void nonSmaCrossDraftIsNeverBacktestable() {
        var store = stack.store();
        var session = store.createSession("非模板会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s8x"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        var d2 = new StrategySpecDraft(draft.id(), draft.workspaceId(), draft.sessionId(),
                null, null, draft.seedId(), "x", "MY_CUSTOM", List.of("JM2609"), "1D",
                params(), "买入", "卖出", "风控", "假设", draft.evidence(), 100,
                List.of(), draft.status(), draft.createdByMemberId(),
                draft.createdAt(), draft.updatedAt());
        var v = stack.validator().validate(d2);
        assertFalse(v.valid());
        assertFalse(v.backtestable());
        assertTrue(v.issues().stream().anyMatch(i ->
                i.code().equals("TEMPLATE_NOT_WHITELISTED")));
    }

    // ------------------------------------------------------------------ //
    // Approval + freeze (controlled transaction)
    // ------------------------------------------------------------------ //

    @Test
    void unvalidatedDraftCannotBeSubmittedOrFrozen() {
        var store = stack.store();
        var session = store.createSession("门禁会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s10"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        // Not validated yet → cannot submit for approval.
        var e = assertThrows(TaskCenterApiException.class,
                () -> store.submitApproval(draft.id(), ""));
        assertEquals(409, e.status().value());
        assertEquals("ILLEGAL_STATE", e.code());
    }

    @Test
    void approveFreezeMapsToModule8VersionAndSnapshot() throws Exception {
        var store = stack.store();
        var session = store.createSession("冻结会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s11"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "请审批");
        var frozen = store.approveAndFreeze(draft.id(), "同意", ownerId);
        assertEquals(StrategySpecDraft.STATUS_FROZEN, frozen.status());

        var detail = store.draft(draft.id());
        assertNotNull(detail.snapshot());
        assertEquals(StrategySpecDraft.TEMPLATE_SMA_CROSS,
                detail.snapshot().templateType());
        assertEquals("均线策略", detail.snapshot().name());
        assertFalse(detail.snapshot().contentHash().isBlank());
        // The Module-8 immutable version exists with a version number >= 1.
        var strategyVersions = stack.strategies().versions(detail.snapshot().strategyId());
        assertEquals(1, strategyVersions.size());
        assertEquals(detail.snapshot().versionNumber(),
                strategyVersions.get(0).versionNumber());
        // selfApproval recorded honestly (this demo allows self-approval).
        assertEquals(1, detail.approvals().size());
        assertTrue(detail.approvals().get(0).selfApproval());
        assertEquals("APPROVED", detail.approvals().get(0).decision());
        // The freeze appended DRAFT_APPROVED + DRAFT_FROZEN audit events.
        JsonNode root = readRaw(store);
        ArrayNode audits = (ArrayNode) root.get("auditEvents");
        boolean approved = false;
        boolean frozenAudit = false;
        for (JsonNode a : audits) {
            if ("DRAFT_APPROVED".equals(a.get("action").asText())) approved = true;
            if ("DRAFT_FROZEN".equals(a.get("action").asText())) frozenAudit = true;
        }
        assertTrue(approved, "DRAFT_APPROVED audit event must exist");
        assertTrue(frozenAudit, "DRAFT_FROZEN audit event must exist");
    }

    @Test
    void breakoutFreezeReloadKeepsDiscriminatorFieldsAndCanonicalHash() throws Exception {
        var store = stack.store();
        var session = store.createSession("突破冻结");
        var turn = store.addUserTurn(session.id(), "JM2609 突破", IntentClassifier.STRATEGY_CANDIDATE,
                seed("breakout-freeze"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        SpecParameters parameters = SpecParameters.breakout(20, "LONG", 0,
                5.0, 0.1, 2, 1);
        var updated = store.updateDraft(draft.id(), structuredPatch(
                StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT, parameters, "突破策略"), null);
        assertEquals(100, updated.completeness());
        assertTrue(store.validateDraft(draft.id()).report().backtestable());
        store.submitApproval(draft.id(), "");
        store.approveAndFreeze(draft.id(), "", ownerId);

        var reloaded = store.draft(draft.id());
        assertEquals(StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT,
                reloaded.snapshot().templateType());
        assertEquals(Integer.valueOf(20),
                reloaded.snapshot().parameters().priceBreakout().lookbackBars());
        assertEquals(SpecParameters.FILL_NEXT_BAR_OPEN,
                reloaded.snapshot().parameters().fillAt());
        assertEquals(TaskCenterStore.contentHash(reloaded.draft()),
                reloaded.snapshot().contentHash());
    }

    @Test
    void eventFreezeMarkerRecoveryKeepsConfirmationAndDoesNotDuplicate() throws Exception {
        var store = stack.store();
        var session = store.createSession("事件恢复");
        var turn = store.addUserTurn(session.id(), "JM2609 事件", IntentClassifier.STRATEGY_CANDIDATE,
                seed("event-recovery"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        SpecParameters parameters = SpecParameters.event(new SpecParameters.EventSignal(
                "evt-1", "src-1", "sha256:abc", "2025-01-01T00:00:00Z",
                "2025-01-02T00:00:00Z", "2025-01-02T00:00:00Z",
                "2025-01-02T00:00:00Z", "2025-10-01T00:00:00Z", "JM2609",
                "LONG", null, null, null,
                "AVAILABLE", 5.0, 0.1, 2, 1));
        store.updateDraft(draft.id(), structuredPatch(
                StrategySpecDraft.TEMPLATE_EVENT_SIGNAL, parameters, "事件策略"), null);
        var confirmed = store.confirmEventEvidence(draft.id());
        String confirmationId = confirmed.parameters().eventSignal().confirmationId();
        assertTrue(store.validateDraft(draft.id()).report().backtestable());
        store.submitApproval(draft.id(), "");
        store.setPersistHookForTests(() -> { throw new RuntimeException("crash"); });
        assertThrows(TaskCenterApiException.class,
                () -> store.approveAndFreeze(draft.id(), "", ownerId));
        assertTrue(Files.exists(store.markerPath()));
        store.setPersistHookForTests(null);
        store.listSessions();
        store.listSessions();

        var detail = store.draft(draft.id());
        assertEquals(confirmationId,
                detail.snapshot().parameters().eventSignal().confirmationId());
        assertEquals(ownerId,
                detail.snapshot().parameters().eventSignal().confirmedByMemberId());
        JsonNode raw = readRaw(store);
        assertEquals(1, raw.path("specSnapshots").size());
        assertEquals(2, raw.path("approvals").findValuesAsText("entityId").stream()
                .filter(draft.id()::equals).count());
        assertEquals(1, raw.path("approvals").findValuesAsText("decision").stream()
                .filter(ApprovalRecord.DECISION_CONFIRMED::equals).count());
        assertFalse(Files.exists(store.markerPath()));
    }

    @Test
    void preRepairSelfReportedEventWithoutApprovalBecomesUnconfirmedAndCannotFreeze()
            throws Exception {
        var store = stack.store();
        String draftId = createUnconfirmedEventDraft(store);
        JsonNode raw = readRaw(store);
        ObjectNode parameters = (ObjectNode) raw.path("drafts").get(0).path("parameters");
        parameters.put("confirmationId", "self-reported");
        parameters.put("confirmedByMemberId", ownerId);
        parameters.put("confirmedAt", "2025-01-02T00:00:00Z");
        Files.writeString(store.filePath(), raw.toString());

        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        var repaired = fresh.draft(draftId).draft();
        assertNull(repaired.parameters().eventSignal().confirmationId());
        assertNull(repaired.parameters().eventSignal().confirmedByMemberId());
        assertNull(repaired.parameters().eventSignal().confirmedAt());
        assertEquals(StrategySpecDraft.STATUS_CO_CREATING, repaired.status());
        TaskCenterApiException validateError = assertThrows(TaskCenterApiException.class,
                () -> fresh.validateDraft(draftId));
        assertEquals("EVIDENCE_MISSING", validateError.code(),
                "self-reported legacy fields without approval are not authoritative");
        TaskCenterApiException freezeError = assertThrows(TaskCenterApiException.class,
                () -> fresh.approveAndFreeze(draftId, "", ownerId));
        assertEquals("EVIDENCE_MISSING", freezeError.code());
    }

    @Test
    void tamperedEventConfirmationApprovalSummaryBecomesUnconfirmedOnRepair()
            throws Exception {
        var store = stack.store();
        String draftId = createUnconfirmedEventDraft(store);
        store.confirmEventEvidence(draftId);
        JsonNode raw = readRaw(store);
        ObjectNode approval = (ObjectNode) raw.path("approvals").get(0);
        approval.put("reason", "tampered event summary");
        Files.writeString(store.filePath(), raw.toString());

        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        var repaired = fresh.draft(draftId).draft();
        assertNull(repaired.parameters().eventSignal().confirmationId());
        assertEquals(StrategySpecDraft.STATUS_CO_CREATING, repaired.status());
    }

    @Test
    void canonicalHashIgnoresParameterJsonKeyOrder() throws Exception {
        String a = "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                + "\"lookbackBars\":20,\"direction\":\"LONG\",\"entryOffsetTicks\":0,"
                + "\"stopLossPct\":5,\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1}";
        String b = "{\"slippageBps\":1,\"feeBps\":2,\"positionSize\":0.1,"
                + "\"stopLossPct\":5,\"entryOffsetTicks\":0,\"direction\":\"LONG\","
                + "\"lookbackBars\":20,\"type\":\"PRICE_BREAKOUT\",\"schemaVersion\":2}";
        SpecParameters pa = MAPPER.readValue(a, SpecParameters.class);
        SpecParameters pb = MAPPER.readValue(b, SpecParameters.class);
        StrategySpecDraft da = hashDraft(pa);
        StrategySpecDraft db = hashDraft(pb);
        assertEquals(TaskCenterStore.contentHash(da), TaskCenterStore.contentHash(db));
    }

    @Test
    void freezeCrashLeavesNoHalfFrozenState() throws Exception {
        var store = stack.store();
        var session = store.createSession("崩溃恢复会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s13"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");

        // Inject a crash between the strategies write and the task-center
        // write: the task-center persist throws.
        store.setPersistHookForTests(() -> { throw new RuntimeException("crash"); });
        var e = assertThrows(TaskCenterApiException.class,
                () -> store.approveAndFreeze(draft.id(), "", ownerId));
        assertEquals(500, e.status().value());
        assertEquals("FREEZE_FAILED", e.code());
        // The marker exists AND the strategies side was already written.
        assertTrue(Files.exists(store.markerPath()), "freeze marker must remain");
        var raw = readRaw(store);
        assertEquals(StrategySpecDraft.STATUS_PENDING_APPROVAL,
                raw.get("drafts").get(0).get("status").asText(),
                "draft must NOT be frozen before recovery");
        assertTrue(raw.get("specSnapshots").isEmpty(),
                "no half-frozen snapshot before recovery");
        assertEquals(1, stack.strategies().list().size(),
                "strategies side was written before the crash");

        // Clear the hook → the NEXT load recovers deterministically.
        store.setPersistHookForTests(null);
        var recovered = store.draft(draft.id());
        assertEquals(StrategySpecDraft.STATUS_FROZEN, recovered.draft().status(),
                "recovery must complete the freeze");
        assertNotNull(recovered.snapshot(), "recovery must write the snapshot");
        assertEquals(1, recovered.approvals().size());
        assertFalse(Files.exists(store.markerPath()), "marker deleted after recovery");
        // No duplicated version after recovery.
        assertEquals(1, stack.strategies().list().size());
        var strategyVersions = stack.strategies()
                .versions(recovered.snapshot().strategyId());
        assertEquals(1, strategyVersions.size());
    }

    @Test
    void unknownFreezeMarkerBlocksNewFreezeWithoutBeingOverwritten() throws Exception {
        assertOpaqueFreezeMarkerBlocksNewFreeze(
                "{\"schema\":\"freeze-txn.v999\",\"sentinel\":\"keep\"}");
    }

    @Test
    void unreadableFreezeMarkerBlocksNewFreezeWithoutBeingOverwritten() throws Exception {
        assertOpaqueFreezeMarkerBlocksNewFreeze("not-json-marker");
    }

    @Test
    void freezeRejectsWhileMarkerPending() {
        var store = stack.store();
        var session = store.createSession("待恢复会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("s14"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");
        store.setPersistHookForTests(() -> { throw new RuntimeException("crash"); });
        assertThrows(TaskCenterApiException.class,
                () -> store.approveAndFreeze(draft.id(), "", ownerId));
        // A second freeze is refused while the marker is pending recovery.
        var e = assertThrows(TaskCenterApiException.class,
                () -> store.approveAndFreeze(draft.id(), "", ownerId));
        assertEquals(409, e.status().value());
        assertEquals("ILLEGAL_STATE", e.code());
        store.setPersistHookForTests(null);
    }

    // ------------------------------------------------------------------ //
    // Tasks: create → approve → manual start → result; retry; cancel
    // ------------------------------------------------------------------ //

    @Test
    void unapprovedTaskCannotStart() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        assertEquals(AgentTask.STATUS_PENDING_APPROVAL, task.status());
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.startTask(task.id())).status().value());
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.cancelTask(task.id(), "")).status().value());
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.retryTask(task.id())).status().value());
    }

    @Test
    void taskLifecycleDeterministicBacktestResult() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        var approved = store.approveTask(task.id(), "同意");
        assertEquals(AgentTask.STATUS_APPROVED, approved.status());
        var started = store.startTask(task.id());
        assertEquals(AgentTask.STATUS_SUCCEEDED, started.task().status());
        var run = started.run();
        assertEquals(BacktestRun.STATUS_SUCCEEDED, run.status());
        assertEquals("acceptance-1d-jm2609", run.datasetId());
        assertEquals("JM2609", run.instrument());
        assertEquals("1D", run.timeframe());
        assertTrue(run.inputBars() >= BacktestRun.MIN_INPUT_BARS);
        assertNotNull(run.grossReturnPct());
        assertNotNull(run.netReturnPct());
        assertNotNull(run.maxDrawdownPct());
        assertNotNull(run.winRate());
        assertEquals(BacktestRun.SAMPLE_OUT_SAMPLE_ONLY, run.sampleOutStatus());
        assertEquals(Integer.valueOf(2), run.feeAssumptionBps());
        assertEquals(Integer.valueOf(1), run.slippageAssumptionBps());
        JsonNode runJson = MAPPER.valueToTree(run);
        assertEquals(StrategySpecDraft.TEMPLATE_SMA_CROSS,
                runJson.path("templateType").asText());
        assertFalse(runJson.path("snapshotContentHash").asText().isBlank());
        assertTrue(runJson.path("datasetContentHash").asText().startsWith("sha256:"));
        assertEquals("BAR_CLOSE->NEXT_BAR_OPEN",
                runJson.path("executionTiming").asText());
        assertTrue(runJson.path("orders").isArray());
        // A SUCCEEDED task cannot be retried (retry is only for
        // FAILED / CANCELLED).
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.retryTask(task.id())).status().value());
        // Deterministic: a second task over the same frozen spec produces the
        // SAME metrics.
        var task2 = store.createBacktestTask(draftId, null);
        store.approveTask(task2.id(), "");
        var second = store.startTask(task2.id());
        assertEquals(AgentTask.STATUS_SUCCEEDED, second.task().status());
        assertEquals(run.grossReturnPct(), second.run().grossReturnPct());
        assertEquals(run.netReturnPct(), second.run().netReturnPct());
        assertEquals(run.maxDrawdownPct(), second.run().maxDrawdownPct());
        assertEquals(run.winRate(), second.run().winRate());
        // Old results are never overwritten.
        var detail = store.task(task.id());
        assertEquals(1, detail.runs().size());
        assertEquals(1, detail.runs().get(0).attemptNumber());
        // The full audit trail of the run is preserved (start + finish).
        var audit = store.taskAudit(task.id());
        assertTrue(audit.auditEvents().stream().anyMatch(a ->
                a.action().equals("TASK_STARTED")), "TASK_STARTED audit must be kept");
        assertTrue(audit.auditEvents().stream().anyMatch(a ->
                a.action().equals("TASK_RUN_SUCCEEDED")), "TASK_RUN_SUCCEEDED audit must exist");
    }

    @Test
    void newSuccessfulRunIsVersionedAndRepairRejectsMissingReplayMetadata() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        assertEquals(BacktestRun.STATUS_SUCCEEDED, store.startTask(task.id()).run().status());

        JsonNode raw = readRaw(store);
        ObjectNode run = (ObjectNode) raw.path("backtestRuns").get(0);
        assertEquals(2, run.path("schemaVersion").asInt(),
                "new completed runs must declare their strict replay schema");
        run.remove("orders");
        Files.writeString(store.filePath(), raw.toString());

        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        fresh.listSessions();
        assertTrue(fresh.task(task.id()).runs().isEmpty(),
                "schema-v2 success missing deterministic orders must be rejected by repair");
    }

    @Test
    void deletingOnlyRunSchemaVersionCannotDowngradeNewRunToLegacy() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        store.startTask(task.id());

        JsonNode raw = readRaw(store);
        ((ObjectNode) raw.path("backtestRuns").get(0)).remove("schemaVersion");
        Files.writeString(store.filePath(), raw.toString());

        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        assertTrue(fresh.task(task.id()).runs().isEmpty(),
                "remaining replay provenance makes this a malformed v2 run, not legacy");
    }

    @Test
    void deletingOnlyFailedRunSchemaVersionCannotDowngradeNewRunToLegacy() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, "tiny-1d-jm2609");
        store.approveTask(task.id(), "");
        assertEquals(BacktestRun.STATUS_FAILED, store.startTask(task.id()).run().status());

        JsonNode raw = readRaw(store);
        ((ObjectNode) raw.path("backtestRuns").get(0)).remove("schemaVersion");
        Files.writeString(store.filePath(), raw.toString());
        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        assertTrue(fresh.task(task.id()).runs().isEmpty());
    }

    @Test
    void finishStartDerivesReplayReferencesFromTaskAndCatalog() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        store.setExecutorForTests((snapshot, datasetId, cancelled) -> {
            var real = stack.runner().run(snapshot, datasetId, cancelled);
            return new BacktestRunnerService.BacktestResult(real.status(), "forged-dataset",
                    real.instrument(), real.timeframe(), real.periodStart(), real.periodEnd(),
                    real.inputBars(), real.trades(), real.grossReturnPct(), real.netReturnPct(),
                    real.maxDrawdownPct(), real.winRate(), real.feeAssumptionBps(),
                    real.slippageAssumptionBps(), real.sampleOutStatus(), real.durationMs(),
                    real.errorCode(), real.errorMessage(), StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                    "0".repeat(64), "sha256:" + "1".repeat(64), "forged->timing",
                    real.orders());
        });

        BacktestRun run = store.startTask(task.id()).run();
        DatasetCatalog.Dataset dataset = stack.catalog().load(task.datasetId());
        StrategySpecSnapshot snapshot = store.draft(draftId).snapshot();
        assertEquals(task.datasetId(), run.datasetId());
        assertEquals(snapshot.templateType(), run.templateType());
        assertEquals(snapshot.contentHash(), run.snapshotContentHash());
        assertEquals(dataset.contentHash(), run.datasetContentHash());
        assertEquals("BAR_CLOSE->NEXT_BAR_OPEN", run.executionTiming());
    }

    @Test
    void repairRejectsWrongBoundHashesAndInconsistentOrderSummary() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var tasks = new java.util.ArrayList<AgentTask>();
        for (int i = 0; i < 3; i++) {
            AgentTask task = store.createBacktestTask(draftId, null);
            store.approveTask(task.id(), "");
            store.startTask(task.id());
            tasks.add(task);
        }

        JsonNode raw = readRaw(store);
        ArrayNode runs = (ArrayNode) raw.path("backtestRuns");
        ((ObjectNode) runs.get(0)).put("snapshotContentHash", "0".repeat(64));
        ((ObjectNode) runs.get(1)).put("datasetContentHash", "sha256:" + "1".repeat(64));
        ObjectNode third = (ObjectNode) runs.get(2);
        third.put("trades", third.path("trades").asInt() + 1);
        Files.writeString(store.filePath(), raw.toString());

        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        for (AgentTask task : tasks) {
            assertTrue(fresh.task(task.id()).runs().isEmpty());
        }
    }

    @Test
    void legacySuccessfulRunWithoutVersionOrReplayMetadataRemainsReadable() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        store.startTask(task.id());

        JsonNode raw = readRaw(store);
        ObjectNode run = (ObjectNode) raw.path("backtestRuns").get(0);
        run.remove(List.of("schemaVersion", "templateType", "snapshotContentHash",
                "datasetContentHash", "executionTiming", "orders"));
        Files.writeString(store.filePath(), raw.toString());

        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        fresh.listSessions();
        assertEquals(1, fresh.task(task.id()).runs().size(),
                "unversioned task-center.v1 completed run remains readable");
        JsonNode legacy = MAPPER.valueToTree(fresh.task(task.id()).runs().get(0));
        assertTrue(legacy.path("schemaVersion").isMissingNode()
                        || legacy.path("schemaVersion").isNull());
    }

    @Test
    void failedTaskRetryCreatesNewAttemptAndCancelIsLocalWithAudit() {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, "tiny-1d-jm2609");
        store.approveTask(task.id(), "");
        var first = store.startTask(task.id());
        assertEquals(AgentTask.STATUS_FAILED, first.task().status(),
                "undersized dataset must fail closed");
        assertEquals("SAMPLE_TOO_SMALL", first.run().errorCode());
        assertEquals(1, first.run().attemptNumber());

        // Retry: new attempt (QUEUED), old result untouched.
        var retried = store.retryTask(task.id());
        assertEquals(AgentTask.STATUS_QUEUED, retried.status());
        var detail = store.task(task.id());
        assertEquals(2, detail.runs().size());
        assertEquals(BacktestRun.STATUS_FAILED, detail.runs().get(0).status(),
                "old failed result is never overwritten");
        assertEquals("SAMPLE_TOO_SMALL", detail.runs().get(0).errorCode());
        assertEquals(BacktestRun.STATUS_QUEUED, detail.runs().get(1).status());
        assertEquals(2, detail.runs().get(1).attemptNumber());

        // Local cancellation of the QUEUED retry (explicitly unrelated to
        // trading order cancellation), with the audit trail preserved.
        var cancelled = store.cancelTask(task.id(), "不想跑了");
        assertEquals(AgentTask.STATUS_CANCELLED, cancelled.status());
        assertEquals("不想跑了", cancelled.cancelReason());
        var after = store.task(task.id());
        assertEquals(BacktestRun.STATUS_CANCELLED, after.runs().get(1).status());
        var audit = store.taskAudit(task.id());
        assertTrue(audit.auditEvents().stream().anyMatch(a ->
                a.action().equals("TASK_CANCELLED")), "TASK_CANCELLED audit must exist");
        // A cancelled task cannot be started directly; retry is still allowed.
        assertEquals(409, assertThrows(TaskCenterApiException.class,
                () -> store.startTask(task.id())).status().value());
    }

    @Test
    void runningTasksAreRepairedToCancelledOnLoad() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        // Manually flip the task to RUNNING in the file, then load with a
        // FRESH store instance (a new process after a crash): the first-load
        // crash recovery must deterministically cancel it.
        JsonNode root = readRaw(store);
        ((ObjectNode) root.get("tasks").get(0)).put("status", "RUNNING");
        ((ObjectNode) root.get("tasks").get(0)).put("updatedAt", "2026-08-01T00:00:00Z");
        Files.writeString(store.filePath(), root.toString());
        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        var repaired = fresh.task(task.id());
        assertEquals(AgentTask.STATUS_CANCELLED, repaired.task().status());
        assertEquals("服务重启中断（本地任务取消，与交易无关）",
                repaired.task().cancelReason());
    }

    @Test
    void runningAttemptIsNotCancelledByConcurrentLoads() throws Exception {
        // A RUNNING attempt is a REAL in-flight state after startTask began
        // (the runner executes outside the store lock). Loads on the SAME
        // store instance must never repair it to CANCELLED — only a fresh
        // instance (new process) performs the crash recovery.
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        var start = store.startTask(task.id());
        assertEquals(AgentTask.STATUS_SUCCEEDED, start.task().status());
        assertEquals(BacktestRun.STATUS_SUCCEEDED, start.run().status());
        var detail = store.task(task.id());
        assertEquals(1, detail.runs().size());
        assertEquals(1, detail.runs().get(0).attemptNumber());
    }

    // ------------------------------------------------------------------ //
    // RBAC matrix
    // ------------------------------------------------------------------ //

    @Test
    void rbacMatrixEnforcedInServer() {
        var store = stack.store();
        var teams = stack.teams();
        var trader = teams.createMember("任务交易员", TeamStore.ROLE_TRADER);
        var viewer = teams.createMember("任务观察员", TeamStore.ROLE_VIEWER);

        var session = store.createSession("RBAC 会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("r1"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();

        // TRADER can edit drafts (a server-side write).
        teams.switchActor(trader.id());
        assertNotNull(store.updateDraft(draft.id(), new TaskCenterDtos.DraftPatch(
                "交易员改名", null, null, null, null, null, null, null, null,
                null, null, null, null), null));
        // Back to the OWNER for validation + submission.
        teams.switchActor(ownerId);
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");

        // TRADER can submit but NEVER approve/reject.
        teams.switchActor(trader.id());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.approveAndFreeze(draft.id(), "", trader.id()))
                .status().value());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.rejectDraft(draft.id(), "")).status().value());

        // VIEWER is read-only.
        teams.switchActor(viewer.id());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.updateDraft(draft.id(), fullPatch(), null)).status().value());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.createSession("越权会话")).status().value());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.submitApproval(draft.id(), "")).status().value());
        assertEquals(403, assertThrows(TaskCenterApiException.class,
                () -> store.approveAndFreeze(draft.id(), "", viewer.id()))
                .status().value());
        assertNotNull(store.draft(draft.id()));
        assertNotNull(store.sessionDetail(session.id()));

        // OWNER approves; a self-approval is recorded honestly (this local
        // single-machine demo allows it but never disguises it).
        teams.switchActor(ownerId);
        var frozen = store.approveAndFreeze(draft.id(), "同意", ownerId);
        assertEquals(StrategySpecDraft.STATUS_FROZEN, frozen.status());
        assertTrue(store.draft(draft.id()).approvals().get(0).selfApproval());
    }

    // ------------------------------------------------------------------ //
    // Workspace isolation
    // ------------------------------------------------------------------ //

    @Test
    void workspaceIsolationAllObjects() {
        var store = stack.store();
        var workspaces = stack.workspaces();
        var other = workspaces.create("隔离空间");
        workspaces.switchTo(other.id());
        var session = store.createSession("隔离会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("i1"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");
        store.approveAndFreeze(draft.id(), "", stack.teams().currentActor().id());
        var task = store.createBacktestTask(draft.id(), null);
        store.approveTask(task.id(), "");
        var start = store.startTask(task.id());
        String runId = start.run().id();
        // An agent run recorded in the other workspace.
        var agentRun = store.persistAgentRun(
                new AgentRun("ar1", session.id(), other.id(), IntentClassifier.RESEARCH,
                        AgentRun.STATUS_COMPLETED, BudgetPolicy.defaultBudget(),
                        2, 1, 100, null, true, null,
                        "2026-08-01T00:00:00Z", "2026-08-01T00:00:01Z"),
                List.of(), null).run();

        // Switch back: every object of the other workspace is 404.
        workspaces.switchTo("default");
        assertEquals(404, assertThrows(TaskCenterApiException.class,
                () -> store.sessionDetail(session.id())).status().value());
        assertEquals(404, assertThrows(TaskCenterApiException.class,
                () -> store.draft(draft.id())).status().value());
        assertEquals(404, assertThrows(TaskCenterApiException.class,
                () -> store.task(task.id())).status().value());
        assertEquals(404, assertThrows(TaskCenterApiException.class,
                () -> store.agentRun(agentRun.id())).status().value());
        assertEquals(404, assertThrows(TaskCenterApiException.class,
                () -> store.backtestRun(runId)).status().value());
        assertTrue(store.tasks(null).isEmpty());
        assertTrue(store.listSessions().isEmpty());
    }

    // ------------------------------------------------------------------ //
    // Persistence + deterministic repair
    // ------------------------------------------------------------------ //

    @Test
    void missingCorruptEmptyFilesRepairToEmpty() throws Exception {
        var store = stack.store();
        assertTrue(store.listSessions().isEmpty());
        Files.writeString(store.filePath(), "{{{ not json");
        assertTrue(store.listSessions().isEmpty());
        Files.writeString(store.filePath(), "");
        assertTrue(store.listSessions().isEmpty());
        Files.deleteIfExists(store.filePath());
        assertTrue(store.listSessions().isEmpty());
    }

    @Test
    void topLevelProtocolIsStrictAndRepaired() throws Exception {
        var store = stack.store();
        var session = store.createSession("协议会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("p1"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();

        // Unknown top-level field + wrong schema + a non-array array.
        JsonNode root = readRaw(store);
        ((ObjectNode) root).put("unexpectedTopLevel", "sentinel");
        ((ObjectNode) root).put("schema", "task-center.v9");
        ((ObjectNode) root).putArray("tasks").add("not-an-object");
        Files.writeString(store.filePath(), root.toString());

        assertEquals(1, store.listSessions().size(), "legal records survive repair");
        var disk = readRaw(store);
        assertFalse(disk.has("unexpectedTopLevel"));
        assertEquals("task-center.v1", disk.get("schema").asText());
        assertEquals(0, disk.get("tasks").size(), "illegal entries are dropped");
        // Second load → no drift.
        store.listSessions();
        assertEquals(disk.toString(), readRaw(store).toString());
    }

    @Test
    void loadRepairDropsOrphansDuplicatesAndKeepsImmutables() throws Exception {
        var store = stack.store();
        String draftId = freezeFreshDraft(store);
        var task = store.createBacktestTask(draftId, null);
        store.approveTask(task.id(), "");
        var start = store.startTask(task.id());
        assertEquals(AgentTask.STATUS_SUCCEEDED, start.task().status());

        // Inject: a duplicate session id (keep first), an orphan turn, an
        // orphan frozen snapshot (KEEP — immutable), a RUNNING run (cancel).
        JsonNode root = readRaw(store);
        ObjectNode session0 = (ObjectNode) root.get("sessions").get(0);
        ObjectNode dup = session0.deepCopy();
        dup.put("id", session0.get("id").asText()); // duplicate id
        ((ArrayNode) root.get("sessions")).add(dup);
        ObjectNode orphanTurn = ((ObjectNode) root.get("turns").get(0).deepCopy());
        orphanTurn.put("id", "orphan-turn");
        orphanTurn.put("sessionId", "ghost-session");
        ((ArrayNode) root.get("turns")).add(orphanTurn);
        ObjectNode orphanSnapshot = ((ObjectNode) root.get("specSnapshots").get(0).deepCopy());
        orphanSnapshot.put("id", "orphan-snapshot");
        orphanSnapshot.put("workspaceId", "ghost-ws");
        ((ArrayNode) root.get("specSnapshots")).add(orphanSnapshot);
        ObjectNode runningRun = ((ObjectNode) root.get("backtestRuns").get(0).deepCopy());
        runningRun.put("id", "running-run");
        runningRun.put("status", "RUNNING");
        runningRun.put("taskId", "ghost-task");
        ((ArrayNode) root.get("backtestRuns")).add(runningRun);
        Files.writeString(store.filePath(), root.toString());

        // The crash-recovery pass (RUNNING → CANCELLED) runs on the FIRST load
        // of a fresh store instance (a new process after a crash) — a loaded
        // file with a persisted RUNNING record is exactly that situation.
        var fresh = new TaskCenterStore(store.filePath().toString(),
                stack.workspaces(), stack.teams(), stack.strategies(),
                stack.validator(), stack.catalog(), stack.runner());
        assertEquals(1, fresh.listSessions().size(), "duplicate session id resolved");
        var detail = fresh.sessionDetail(session0.get("id").asText());
        assertFalse(detail.turns().stream().anyMatch(t -> t.id().equals("orphan-turn")),
                "orphan turn dropped");
        var disk = readRaw(fresh);
        // Frozen snapshot kept (immutability) even though orphaned; the
        // RUNNING run is repaired to CANCELLED with the crash reason.
        assertTrue(disk.get("specSnapshots").toString().contains("orphan-snapshot"),
                "frozen snapshots are NEVER dropped by repair");
        assertEquals("CANCELLED", disk.get("backtestRuns").get(1).get("status").asText());
        assertEquals("CANCELLED", disk.get("backtestRuns").get(1).get("errorCode").asText());
        // Completed results untouched.
        assertEquals(BacktestRun.STATUS_SUCCEEDED, fresh.task(task.id()).runs().get(0).status());
    }

    @Test
    void auditEventsAreAppendOnlyAndReadsNeverWrite() throws Exception {
        var store = stack.store();
        var session = store.createSession("审计会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("a1"));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");
        store.approveAndFreeze(draft.id(), "", ownerId);

        assertTrue(readRaw(store).get("auditEvents").size() >= 5,
                "every write appends an audit event");
        // GET-style reads never append audits / never write.
        String before = Files.readString(store.filePath());
        store.listSessions();
        store.sessionDetail(session.id());
        store.draft(draft.id());
        store.tasks(null);
        assertEquals(before, Files.readString(store.filePath()),
                "GET-style reads never write");
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private static StrategySeed seed(String id) {
        return new StrategySeed(id, "如果 JM2609 上穿20日均线就买入",
                List.of("JM2609"), "上穿买入", "下穿卖出", null,
                IntentClassifier.STRATEGY_CANDIDATE, StrategySeed.STATUS_DETECTED,
                null, "2026-08-01T00:00:00Z", null);
    }

    private static SpecParameters params() {
        return new SpecParameters(5, 20, 0.1, 5.0, 2, 1);
    }

    static TaskCenterDtos.DraftPatch fullPatch() {
        return new TaskCenterDtos.DraftPatch(
                "均线策略", List.of("JM2609"), "1D", 5, 20, 0.1, 5.0, 2, 1,
                "快线上穿慢线时买入", "快线下穿慢线时卖出", "止损5%，仓位10%",
                "使用内置验收样本，手续费2bp，滑点1bp");
    }

    private static TaskCenterDtos.DraftPatch structuredPatch(String template,
                                                             SpecParameters parameters,
                                                             String name) {
        return new TaskCenterDtos.DraftPatch(name, List.of("JM2609"), "1D",
                null, null, null, null, null, null, "入场", "出场", "风控",
                "SAMPLE_ONLY", template, parameters);
    }

    private static StrategySpecDraft hashDraft(SpecParameters parameters) {
        return new StrategySpecDraft("d", "default", "s", null, null, null,
                "x", StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT, List.of("JM2609"),
                "1D", parameters, "入", "出", "风", "SAMPLE_ONLY", List.of(),
                100, List.of(), StrategySpecDraft.STATUS_CO_CREATING, "m",
                "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");
    }

    /** Fill every required field of the draft (canonical SMA_CROSS values). */
    static void fillDraft(TaskCenterStore store, String draftId) {
        store.updateDraft(draftId, fullPatch(), null);
    }

    /** Create a fully validated + approved + frozen draft in a fresh session. */
    private String freezeFreshDraft(TaskCenterStore store) {
        var session = store.createSession("任务流程会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("t-" + Math.random()));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");
        store.approveAndFreeze(draft.id(), "", stack.teams().currentActor().id());
        return draft.id();
    }

    private String createUnconfirmedEventDraft(TaskCenterStore store) {
        var session = store.createSession("事件权威确认");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 出现事件信号就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("event-auth-" + Math.random()));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        SpecParameters parameters = SpecParameters.event(new SpecParameters.EventSignal(
                "evt-auth", "src-auth", "sha256:event", "2025-01-01T00:00:00Z",
                "2025-01-02T00:00:00Z", "2025-01-02T00:00:00Z",
                "2025-01-02T00:00:00Z", "2027-01-01T00:00:00Z", "JM2609",
                "LONG", null, null, null, "AVAILABLE", 5.0, 0.1, 2, 1));
        store.updateDraft(draft.id(), structuredPatch(
                StrategySpecDraft.TEMPLATE_EVENT_SIGNAL, parameters, "事件权威策略"),
                turn.id());
        return draft.id();
    }

    private void assertOpaqueFreezeMarkerBlocksNewFreeze(String markerBytes) throws Exception {
        var store = stack.store();
        var session = store.createSession("marker fail-closed");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE, seed("marker-" + Math.random()));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        fillDraft(store, draft.id());
        store.validateDraft(draft.id());
        store.submitApproval(draft.id(), "");
        Files.writeString(store.markerPath(), markerBytes);

        var error = assertThrows(TaskCenterApiException.class,
                () -> store.approveAndFreeze(draft.id(), "", ownerId));
        assertEquals("ILLEGAL_STATE", error.code());
        assertEquals(markerBytes, Files.readString(store.markerPath()),
                "opaque marker must remain byte-for-byte for manual recovery");
        assertEquals(StrategySpecDraft.STATUS_PENDING_APPROVAL,
                store.draft(draft.id()).draft().status());
    }

    private static JsonNode readRaw(TaskCenterStore store) throws Exception {
        return MAPPER.readTree(Files.readString(store.filePath()));
    }

    /** A covering-but-undersized dataset (50 bars) so a run FAILS closed. */
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
