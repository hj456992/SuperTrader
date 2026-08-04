package com.supertrader.demo.taskcenter;

import com.supertrader.demo.team.TeamStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of the Agent Runtime Harness pieces (Module 9): the Capability
 * Registry fail-closed behavior, the ToolProxy gate (schema / workspace /
 * RBAC / timeout / size / masking / trace), the budget → checkpoint rule and
 * the MODEL_UNAVAILABLE path. All offline and deterministic.
 */
class AgentHarnessTest {

    private static TaskCenterTestSupport.Stack stack;
    private static ToolProxy toolProxy;
    private static CapabilityRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        stack = TaskCenterTestSupport.build();
        TaskCenterTestSupport.copyAcceptanceDatasets(stack);
        registry = new CapabilityRegistry();
        toolProxy = new ToolProxy(registry, stack.workspaces());
        // Register exactly the six whitelisted handlers.
        toolProxy.register(CapabilityRegistry.RAG_SEARCH_LOCAL, args -> "知识片段");
        toolProxy.register(CapabilityRegistry.STRATEGY_READ, args -> "draft read");
        toolProxy.register(CapabilityRegistry.STRATEGY_DRAFT_UPDATE, args -> "draft updated");
        toolProxy.register(CapabilityRegistry.STRATEGY_VALIDATE, args -> "validated");
        toolProxy.register(CapabilityRegistry.BACKTEST_PLAN, args -> "planned");
        toolProxy.register(CapabilityRegistry.BACKTEST_RESULT_SUMMARIZE, args -> "summarized");
    }

    @Test
    void capabilityRegistryContainsNoTradingCapability() {
        // Module 10 added the read-only SimNow snapshot capability to the
        // registry (documented allowlist) — it has NO ToolProxy handler, so
        // the Agent path can never invoke it (see RiskApiTest).
        assertEquals(7, CapabilityRegistry.ALLOWED.size());
        assertTrue(CapabilityRegistry.ALLOWED.contains(
                CapabilityRegistry.SIMNOW_READ_ONLY_SNAPSHOT));
        for (String c : CapabilityRegistry.ALLOWED) {
            assertFalse(c.contains("order"));
            assertFalse(c.contains("cancel"));
            assertFalse(c.contains("trade"));
            assertFalse(c.contains("execution-live"));
            assertFalse(c.contains("paper"));
            assertFalse(c.contains("live"));
            assertFalse(c.contains("account-bind"));
            assertFalse(c.contains("auto-recovery"));
        }
        assertFalse(registry.isRegistered("order.insert"));
        assertFalse(registry.isRegistered("trade"));
        assertFalse(registry.isRegistered("live-runner"));
        assertFalse(registry.isRegistered("paper-runner"));
        assertFalse(registry.isRegistered("future-gateway"));
        assertFalse(registry.isRegistered("auto-trade"));
    }

    @Test
    void unregisteredCapabilityIsFailClosed() {
        var r = toolProxy.execute(new ToolProxy.ToolCall("order.insert",
                Map.of(), "default", TeamStore.ROLE_OWNER, "r1", "s1"));
        assertFalse(r.ok());
        assertEquals(ToolProxy.ERR_NOT_REGISTERED, r.errorCode());
        var r2 = toolProxy.execute(new ToolProxy.ToolCall("trade",
                Map.of(), "default", TeamStore.ROLE_OWNER, "r1", "s1"));
        assertEquals(ToolProxy.ERR_NOT_REGISTERED, r2.errorCode());
    }

    @Test
    void registeringAnUnregisteredCapabilityIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> toolProxy.register("order.insert", args -> "x"));
    }

    @Test
    void toolProxySchemaValidation() {
        var r = toolProxy.execute(new ToolProxy.ToolCall(CapabilityRegistry.RAG_SEARCH_LOCAL,
                Map.of(), "default", TeamStore.ROLE_OWNER, "r1", "s1"));
        assertFalse(r.ok());
        assertEquals(ToolProxy.ERR_INVALID_SCHEMA, r.errorCode());
        var r2 = toolProxy.execute(new ToolProxy.ToolCall(CapabilityRegistry.RAG_SEARCH_LOCAL,
                Map.of("query", "均线", "evil", "x"), "default",
                TeamStore.ROLE_OWNER, "r1", "s1"));
        assertEquals(ToolProxy.ERR_INVALID_SCHEMA, r2.errorCode());
    }

    @Test
    void toolProxyWorkspaceCheck() {
        var r = toolProxy.execute(new ToolProxy.ToolCall(CapabilityRegistry.RAG_SEARCH_LOCAL,
                Map.of("query", "均线"), "other-ws", TeamStore.ROLE_OWNER, "r1", "s1"));
        assertFalse(r.ok());
        assertEquals(ToolProxy.ERR_WORKSPACE_MISMATCH, r.errorCode());
    }

    @Test
    void toolProxyRbacCheck() {
        var r = toolProxy.execute(new ToolProxy.ToolCall(CapabilityRegistry.STRATEGY_DRAFT_UPDATE,
                Map.of("field", "name", "value", "x"), "default",
                TeamStore.ROLE_VIEWER, "r1", "s1"));
        assertFalse(r.ok());
        assertEquals(ToolProxy.ERR_FORBIDDEN, r.errorCode());
        // Read capabilities are allowed for VIEWER.
        var ok = toolProxy.execute(new ToolProxy.ToolCall(CapabilityRegistry.RAG_SEARCH_LOCAL,
                Map.of("query", "均线"), "default", TeamStore.ROLE_VIEWER, "r1", "s1"));
        assertTrue(ok.ok());
    }

    @Test
    void toolProxyTimeoutIsEnforced() throws Exception {
        var slow = new ToolProxy(new CapabilityRegistry(),
                new com.supertrader.demo.workspace.WorkspaceStore(
                        java.nio.file.Files.createTempFile("ws-timeout-", ".json").toString()));
        slow.setTimeoutSecondsForTests(1);
        slow.register(CapabilityRegistry.RAG_SEARCH_LOCAL,
                args -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return "too late";
                });
        long started = System.nanoTime();
        var r = slow.execute(new ToolProxy.ToolCall(CapabilityRegistry.RAG_SEARCH_LOCAL,
                Map.of("query", "均线"), "default", TeamStore.ROLE_OWNER, "r1", "s1"));
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertFalse(r.ok());
        assertEquals(ToolProxy.ERR_TIMEOUT, r.errorCode());
        assertTrue(elapsedMs < 5_000, "timeout must fire quickly, took " + elapsedMs + "ms");
    }

    @Test
    void toolProxyOutputSizeLimitTruncates() throws Exception {
        var big = new ToolProxy(new CapabilityRegistry(),
                new com.supertrader.demo.workspace.WorkspaceStore(
                        java.nio.file.Files.createTempFile("ws-big-", ".json").toString()));
        big.register(CapabilityRegistry.RAG_SEARCH_LOCAL,
                args -> "x".repeat(50_000));
        var r = big.execute(new ToolProxy.ToolCall(CapabilityRegistry.RAG_SEARCH_LOCAL,
                Map.of("query", "均线"), "default", TeamStore.ROLE_OWNER, "r1", "s1"));
        assertTrue(r.ok());
        assertTrue(r.truncated());
        assertEquals(BudgetPolicy.DEFAULT_MAX_OUTPUT_CHARS, r.output().length());
    }

    @Test
    void toolProxyMasksSensitiveValuesInOutputAndInput() {
        var r = toolProxy.execute(new ToolProxy.ToolCall(CapabilityRegistry.STRATEGY_READ,
                Map.of("draftId", "d1"), "default", TeamStore.ROLE_OWNER, "r1", "s1"));
        assertTrue(r.ok());
        assertEquals("draft read", r.output());
        assertFalse(r.inputSummary().contains("password"));
        assertTrue(ToolProxy.maskSensitive("password=secret123").contains("password=***"));
        assertTrue(ToolProxy.maskSensitive("authCode: abc123").contains("authCode=***"));
        assertFalse(ToolProxy.maskSensitive("authCode: abc123").contains("abc123"));
        assertFalse(ToolProxy.maskSensitive("正常文本").contains("***"));
    }

    @Test
    void budgetExhaustionSavesCheckpoint() throws Exception {
        var stack2 = TaskCenterTestSupport.build();
        TaskCenterTestSupport.copyAcceptanceDatasets(stack2);
        var harness = new HarnessForTest(stack2);
        var session = stack2.store().createSession("预算会话");
        // A tiny budget: one step and ten output chars — the run must end
        // CHECKPOINTED with a checkpoint record (never an infinite loop).
        var execution = harness.harness.executeTurnWithBudget(session,
                "如果 JM2609 上穿20日均线就买入", null, null, TeamStore.ROLE_OWNER,
                new RunBudget(1, 1, 10));
        assertEquals(AgentRun.STATUS_CHECKPOINTED, execution.run().status());
        assertNotNull(execution.checkpoint());
        assertNotNull(execution.checkpoint().reason());
        // The checkpoint is persisted through the store.
        var persisted = stack2.store().persistAgentRun(execution.run(),
                execution.steps(), execution.checkpoint());
        assertNotNull(persisted.checkpoint());
        assertEquals(execution.run().id(), persisted.run().id());
    }

    @Test
    void noModelKeyProducesModelUnavailableNotFakeAnswers() throws Exception {
        var harness = new HarnessForTest(stack);
        var session = stack.store().createSession("模型不可用会话");
        var execution = harness.harness.executeTurn(session, "你好",
                null, null, TeamStore.ROLE_OWNER);
        assertTrue(execution.modelUnavailable());
        assertTrue(execution.assistantContent().contains("MODEL_UNAVAILABLE"));
        // The intent router still works deterministically.
        assertEquals(IntentClassifier.GENERAL_QA, execution.intent());
    }

    @Test
    void candidateTurnProducesSeedButNoDraft() throws Exception {
        var harness = new HarnessForTest(stack);
        var session = stack.store().createSession("候选检测会话");
        var execution = harness.harness.executeTurn(session,
                "如果 JM2609 上穿20日均线就买入", null, null, TeamStore.ROLE_OWNER);
        assertEquals(IntentClassifier.STRATEGY_CANDIDATE, execution.intent());
        assertNotNull(execution.seed());
        assertEquals(StrategySeed.STATUS_DETECTED, execution.seed().status());
        assertNull(execution.question());
        assertTrue(execution.assistantContent().contains("继续完善策略"));
        // No draft exists yet.
        assertTrue(stack.store().sessionDetail(session.id()).drafts().isEmpty());
    }

    @Test
    void researchTurnReturnsCitationsOrEvidenceMissing() throws Exception {
        var harness = new HarnessForTest(stack);
        var session = stack.store().createSession("研究会话");
        var execution = harness.harness.executeTurn(session,
                "什么是双均线交叉策略？有哪些研究要点？", null, null,
                TeamStore.ROLE_OWNER);
        assertEquals(IntentClassifier.RESEARCH, execution.intent());
        assertTrue(execution.assistantContent().contains("citation")
                || execution.assistantContent().contains("EVIDENCE_MISSING"));
        assertTrue(execution.run().toolCallsUsed() >= 1);
        assertTrue(execution.steps().stream().anyMatch(s ->
                CapabilityRegistry.RAG_SEARCH_LOCAL.equals(s.capability())));
    }

    @Test
    void refinementTurnAppliesSingleQuestionAnswer() throws Exception {
        var harness = new HarnessForTest(stack);
        var store = stack.store();
        var session = store.createSession("完善会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE,
                new StrategySeed("h1", "如果 JM2609 上穿20日均线就买入",
                        java.util.List.of("JM2609"), "上穿买入", "下穿卖出", null,
                        IntentClassifier.STRATEGY_CANDIDATE, StrategySeed.STATUS_DETECTED,
                        null, "2026-08-01T00:00:00Z", null));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        TurnQuestion q = SpecFieldHelpers.nextQuestion(draft);
        assertNotNull(q);
        // Answer the pending question with a valid value for that field.
        String answer = switch (q.field()) {
            case "timeframe" -> "日线";
            case "instruments" -> "JM2609";
            case "name" -> "测试策略";
            case "fastWindow" -> "5";
            case "slowWindow" -> "20";
            case "positionSize" -> "0.1";
            case "stopLossPct" -> "5";
            case "feeBps" -> "2";
            case "slippageBps" -> "1";
            case "backtestAssumptions" -> "使用验收样本";
            case "entryCondition" -> "快线上穿时买入";
            case "exitCondition" -> "快线下穿时卖出";
            case "riskLimits" -> "止损5%";
            default -> "请填写：" + q.field();
        };
        var execution = harness.harness.executeTurn(session, answer,
                draft, q, TeamStore.ROLE_OWNER);
        assertEquals(IntentClassifier.STRATEGY_REFINEMENT, execution.intent());
        assertNotNull(execution.patch(), "the answer must parse into a patch");
        // The confirmed field is never asked again; at most one question stays.
        assertTrue(execution.question() == null
                || !execution.question().field().equals(q.field()),
                "the confirmed field is never asked again");
    }

    @Test
    void sequentialChatAnswersAdvanceTheSingleQuestion() throws Exception {
        var harness = new HarnessForTest(stack);
        var store = stack.store();
        var session = store.createSession("多轮完善会话");
        var turn = store.addUserTurn(session.id(), "如果 JM2609 上穿20日均线就买入",
                IntentClassifier.STRATEGY_CANDIDATE,
                new StrategySeed("h2", "如果 JM2609 上穿20日均线就买入",
                        java.util.List.of("JM2609"), "上穿买入", null, null,
                        IntentClassifier.STRATEGY_CANDIDATE, StrategySeed.STATUS_DETECTED,
                        null, "2026-08-01T00:00:00Z", null));
        var draft = store.decideSeed(session.id(), turn.id(), "CO_CREATE").draft();
        // Round 1: the highest-impact missing field is the timeframe.
        TurnQuestion q1 = SpecFieldHelpers.nextQuestion(draft);
        assertEquals("timeframe", q1.field());
        var e1 = harness.harness.executeTurn(session, "5M", draft, q1,
                TeamStore.ROLE_OWNER);
        assertNotNull(e1.patch());
        assertNull(e1.parseError());
        // The NEXT question is carried on the same assistant turn, so the
        // pending question advances for the next user turn (ONE-question rule).
        assertNotNull(e1.question(),
                "the next field must be asked after a successful answer");
        assertEquals("exitCondition", e1.question().field());
        // Round 2: the answer parses against the NEW pending question, never a
        // stale one (regression: after "5M", a wrong parse of the second answer
        // against timeframe used to happen).
        var draft2 = TaskCenterStore.applyPatch(draft, e1.patch(), null);
        var e2 = harness.harness.executeTurn(session, "快线下穿时卖出", draft2,
                e1.question(), TeamStore.ROLE_OWNER);
        assertEquals("快线下穿时卖出", e2.patch().exitCondition());
        assertNull(e2.parseError());
        assertNotNull(e2.question());
        assertEquals("riskLimits", e2.question().field());
    }

    /** Harness with the same stack (registers handlers + builds agent). */
    private static final class HarnessForTest {
        final AgentRuntimeHarness harness;
        HarnessForTest(TaskCenterTestSupport.Stack stack) {
            var registry = new CapabilityRegistry();
            var proxy = new ToolProxy(registry, stack.workspaces());
            harness = new AgentRuntimeHarness(registry, proxy, stack.knowledge(),
                    null, stack.validator(), stack.catalog(), stack.workspaces());
            // Avoid Spring: invoke init() manually with the knowledge service.
            harnessInit(harness, proxy, stack);
        }
    }

    private static void harnessInit(AgentRuntimeHarness harness, ToolProxy proxy,
                                    TaskCenterTestSupport.Stack stack) {
        // The harness's @PostConstruct registers the six handlers; replicate
        // it here through the same registration API so the harness works
        // without Spring.
        proxy.register(CapabilityRegistry.STRATEGY_READ, args -> "strategy.read ok");
        proxy.register(CapabilityRegistry.STRATEGY_DRAFT_UPDATE, args -> "draft.update ok");
        proxy.register(CapabilityRegistry.STRATEGY_VALIDATE, args -> "validate ok");
        proxy.register(CapabilityRegistry.RAG_SEARCH_LOCAL, args -> {
            var hits = stack.knowledge().search(args.getOrDefault("query", ""));
            return hits.isEmpty() ? "EVIDENCE_MISSING" : "citation found";
        });
        proxy.register(CapabilityRegistry.BACKTEST_PLAN, args -> "plan ok");
        proxy.register(CapabilityRegistry.BACKTEST_RESULT_SUMMARIZE, args -> "summarize ok");
        // No model key → MODEL_UNAVAILABLE path is what we assert.
    }
}
