package com.supertrader.demo.taskcenter;

import com.supertrader.demo.agentscope.AgentScopeBootstrap;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Agent Runtime Harness (Module 9) — the ONLY controlled place where the
 * conversation / strategy co-creation layer and the agent / knowledge layer
 * run. It:
 * <ul>
 *   <li>classifies the intent with the DETERMINISTIC {@link IntentClassifier}
 *       (never an LLM — offline and repeatable);</li>
 *   <li>runs every capability call through the {@link ToolProxy} gate
 *       (registry / schema / workspace / RBAC / timeout / size / masking /
 *       trace);</li>
 *   <li>uses AgentScope (the built ReActAgent) INSIDE the harness for the
 *       conversational reply ONLY when a model key is configured; without a
 *       key every reply is produced by the deterministic planner and clearly
 *       marked MODEL_UNAVAILABLE — model answers are never faked;</li>
 *   <li>enforces the per-run {@link BudgetPolicy} (max steps / tool calls /
 *       output chars) and saves a {@link RunCheckpoint} when the budget is
 *       exhausted — a run can never loop forever;</li>
 *   <li>produces the traced {@link AgentStep}s and the ONE-question-per-turn
 *       rule (at most one highest-impact field question).</li>
 * </ul>
 *
 * <p>This harness NEVER creates a Draft, NEVER freezes, approves or starts
 * anything by itself: plain QA creates no Draft; a detected StrategySeed waits
 * for the user's explicit choice; the highest-impact question is only asked;
 * approvals and task starts are human gates handled by the store/service.
 */
@Component
public class AgentRuntimeHarness {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeHarness.class);
    private static final Duration MODEL_CALL_TIMEOUT = Duration.ofSeconds(30);

    /** The outcome of one harness turn execution. */
    public record TurnExecution(
            String intent, StrategySeed seed, AgentRun run, List<AgentStep> steps,
            RunCheckpoint checkpoint, String assistantContent, TurnQuestion question,
            boolean modelUnavailable, TaskCenterDtos.DraftPatch patch,
            String parseError, IntentResult intentResult) {

        /** Backwards-compatible constructor (no structured IntentResult). */
        public TurnExecution(String intent, StrategySeed seed, AgentRun run,
                             List<AgentStep> steps, RunCheckpoint checkpoint,
                             String assistantContent, TurnQuestion question,
                             boolean modelUnavailable, TaskCenterDtos.DraftPatch patch,
                             String parseError) {
            this(intent, seed, run, steps, checkpoint, assistantContent, question,
                    modelUnavailable, patch, parseError, null);
        }
    }

    private final CapabilityRegistry registry;
    private final ToolProxy toolProxy;
    private final KnowledgeService knowledge;
    private final AgentScopeBootstrap bootstrap;
    private final StrategySpecValidator validator;
    private final DatasetCatalog catalog;
    private final com.supertrader.demo.workspace.WorkspaceStore workspaceStore;

    @Value("${app.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;
    @Value("${app.deepseek.model:deepseek-chat}")
    private String deepseekModel;
    @Value("${app.deepseek.api-key-env:DEEPSEEK_API_KEY}")
    private String apiKeyEnv;
    @Value("${app.agentscope.workspace}")
    private String agentWorkspace;

    private volatile ReActAgent taskCenterAgent;

    public AgentRuntimeHarness(CapabilityRegistry registry, ToolProxy toolProxy,
                               KnowledgeService knowledge, AgentScopeBootstrap bootstrap,
                               StrategySpecValidator validator, DatasetCatalog catalog,
                               com.supertrader.demo.workspace.WorkspaceStore workspaceStore) {
        this.registry = registry;
        this.toolProxy = toolProxy;
        this.knowledge = knowledge;
        this.bootstrap = bootstrap;
        this.validator = validator;
        this.catalog = catalog;
        this.workspaceStore = workspaceStore;
    }

    /** Visible for tests. */
    boolean modelAvailable() {
        return taskCenterAgent != null
                && (bootstrap == null || bootstrap.modelConfigured());
    }

    /** Visible for tests. */
    ReActAgent agent() {
        return taskCenterAgent;
    }

    /**
     * Register the six deterministic capability handlers (all gated by the
     * ToolProxy) and build the AgentScope task-center agent (fault-tolerant).
     */
    @PostConstruct
    void init() {
        // The ONLY registered handlers — exactly the six whitelisted
        // capabilities. Registering anything else throws.
        toolProxy.register(CapabilityRegistry.STRATEGY_READ,
                args -> {
                    String completeness = args.getOrDefault("completeness", "");
                    String missing = args.getOrDefault("missingFields", "");
                    return "strategy.read: completeness=" + completeness
                            + "% missing=[" + missing + "]";
                });
        toolProxy.register(CapabilityRegistry.STRATEGY_DRAFT_UPDATE,
                args -> {
                    String field = args.getOrDefault("field", "");
                    String value = args.getOrDefault("value", "");
                    // Deterministic constraints echo (never mutates anything by
                    // itself — the service persists the validated patch).
                    return "strategy.draft.update: field=" + field
                            + " 的确定性约束为 "
                            + SpecFieldHelpers.QUESTION_PROMPTS.getOrDefault(field,
                                    "未知字段：请先确认字段名");
                });
        toolProxy.register(CapabilityRegistry.STRATEGY_VALIDATE,
                args -> {
                    String draftId = args.getOrDefault("draftId", "");
                    return "strategy.validate: 请先通过 POST /strategy-drafts/{id}/validate "
                            + "运行确定性 Validator（draftId=" + draftId + "）；"
                            + "LLM 无权把失败报告改为通过。";
                });
        toolProxy.register(CapabilityRegistry.RAG_SEARCH_LOCAL,
                args -> {
                    String query = args.getOrDefault("query", "");
                    List<KnowledgeService.KnowledgeHit> hits = knowledge.search(query);
                    if (hits.isEmpty()) {
                        return "EVIDENCE_MISSING：本地知识库没有匹配内容，不凭空补全。";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (KnowledgeService.KnowledgeHit h : hits) {
                        sb.append("[citation sourceId=").append(h.sourceId())
                          .append(" title=").append(h.title())
                          .append(" path=").append(h.relativePath())
                          .append(" sha256=")
                          .append(h.contentHash().substring(0, Math.min(12, h.contentHash().length())))
                          .append("] ").append(h.snippet()).append('\n');
                    }
                    return sb.toString();
                });
        toolProxy.register(CapabilityRegistry.BACKTEST_PLAN,
                args -> {
                    String draftId = args.getOrDefault("draftId", "");
                    String datasetId = args.getOrDefault("datasetId", "");
                    return "backtest.plan: 草案必须已批准冻结且确定性校验通过才能创建回测任务"
                            + "（draftId=" + draftId + "，dataset=" + datasetId + "）；"
                            + "创建后仍需人工审批与人工启动，无自动启动。";
                });
        toolProxy.register(CapabilityRegistry.BACKTEST_RESULT_SUMMARIZE,
                args -> "backtest.result.summarize: 只解释已经存在的结构化结果"
                        + "（runId=" + args.getOrDefault("runId", "")
                        + "）；解释不修改任何数值。内置数据集为验收样本，非真实行情，不构成投资建议。");

        // AgentScope: build the task-center agent inside the harness. Without
        // a key the deterministic planner is used (MODEL_UNAVAILABLE).
        try {
            boolean keyConfigured = bootstrap != null && bootstrap.modelConfigured();
            this.taskCenterAgent = TaskCenterAgentFactory.buildTaskCenterAgent(
                    deepseekBaseUrl, deepseekModel, apiKeyEnv, agentWorkspace,
                    keyConfigured, knowledge);
        } catch (Throwable t) {
            log.warn("task-center AgentScope agent build failed (deterministic planner stays): {}",
                    t.getClass().getSimpleName());
        }
    }

    /**
     * Execute one user turn through the harness: classify → (optionally gate
     * capability calls via the ToolProxy) → produce the deterministic reply +
     * at most ONE question + (optionally) a Draft patch. All steps are traced;
     * the budget is enforced (checkpoint when exhausted).
     *
     * @param session          the owning session
     * @param userContent      the validated user message
     * @param activeDraft      the CO_CREATING draft of the session (nullable)
     * @param pendingQuestion  the question the previous assistant turn asked
     *                         (nullable)
     * @param actorRole        the current actor role (RBAC for capability calls)
     */
    public TurnExecution executeTurn(ConversationSession session, String userContent,
                                     StrategySpecDraft activeDraft,
                                     TurnQuestion pendingQuestion, String actorRole) {
        return executeTurnWithBudget(session, userContent, activeDraft,
                pendingQuestion, actorRole, BudgetPolicy.defaultBudget());
    }

    /**
     * NEW application-layer entry (Task 4): runs the deterministic classifier,
     * the optional model {@link IntentInferencePort} and the
     * {@link IntentReconciler} (rules take priority), then the existing
     * gated capability / budget logic. It fires safe {@link HarnessObserver}
     * events (never hidden chain-of-thought / raw prompts), honours a
     * cooperative cancel token (no new step after cancel), and uses the
     * server-generated {@code runId}. The original 5-arg entry stays
     * regression-compatible (it delegates to the deterministic planner only).
     *
     * <p><b>设计要点（中文阅读说明）</b>：这是 Demo 的唯一 Agent 内核入口，所有模型/
     * Capability 调用必须经过这里。执行顺序严格固定：
     * <ol>
     *   <li>发 {@code run.started}（生命周期事件由 Harness 单一负责，Coordinator 不再重发）。</li>
     *   <li>协作取消检查（已取消则直接 STOPPED，不产生任何 step）。</li>
     *   <li><b>能力越界预路由</b>（P0-4）：在分类与模型调用<b>之前</b>用纯字符串匹配
     *       拒绝「忽略规则/直接下单/调用 CTP」类请求，返回 {@code CAPABILITY_NOT_REGISTERED}，
     *       不调模型、不调 Tool。</li>
     *   <li><b>歧义执行短路</b>（第二轮 Issue #1）：reconcile 后若为 {@code EXECUTION_APPLICATION}
     *       且需澄清，直接生成「回测/模拟/讨论」单一问题并 COMPLETED，不进入 planner。</li>
     *   <li>预算检查、委托确定性 planner（{@link #executeTurnWithBudget}）。</li>
     *   <li><b>候选回复只保留确定性 Seed 说明</b>（第三轮 Issue A）：丢弃模型自由文本，
     *       避免模型在用户确认前追问字段或暗示草案已存在。</li>
     * </ol>
     * 任何高影响动作（执行、回测、交易）都必须经过显式确认，模型置信度永不授权。
     *
     * @param request  server-owned run inputs (runId / session / content /
     *                 activeDraft / pendingQuestion / actorRole / budget /
     *                 cancel token / intent port)
     * @param observer receives safe structured events; pass {@code null} for none
     */
    public TurnExecution executeTurn(HarnessTurnRequest request, HarnessObserver observer) {
        HarnessObserver obs = observer == null ? HarnessObserver.NO_OP : observer;
        IntentReconciler reconciler = new IntentReconciler();
        LoopGuard loopGuard = new LoopGuard(request.budget());

        // run.started — before any step.
        obs.onEvent(HarnessObserver.event("run.started", java.util.Map.of(
                "runId", request.runId(),
                "sessionId", request.session().id(),
                "budget", budgetMap(request.budget()))));

        // Cooperative cancellation: if already cancelled, stop before any work.
        if (request.isCancelled()) {
            loopGuard.cancel();
            AgentRun stopped = terminalRun(request, AgentRun.STATUS_STOPPED, 0, 0, 0, null);
            obs.onEvent(HarnessObserver.event("run.stopped", java.util.Map.of(
                    "runId", request.runId(), "status", "STOPPED", "steps", 0)));
            return new TurnExecution(IntentResult.GENERAL_QA, null, stopped,
                    java.util.List.of(), null, "", null, true, null, null);
        }

        // P0-4 能力越界预路由（中文要点）：在【意图模型和任何 Tool 调用之前】、
        // 在普通问答 planner 路径之前，用 IntentReconciler.isCapabilityBypass(content)
        // 做纯字符串匹配，拒绝「忽略规则/直接下单/调用 CTP/注册交易能力」类请求，
        // 返回稳定错误码 CAPABILITY_NOT_REGISTERED。
        // 为什么放在这里：Prompt 注入无法到达可能被诱导升级的模型；且本路径不导入任何
        // CTP/Order/Gateway 类型（边界扫描会守住）。详细英文说明见下。
        if (IntentReconciler.isCapabilityBypass(request.content())) {
            String code = com.supertrader.demo.taskcenter.ToolProxy.ERR_NOT_REGISTERED; // "CAPABILITY_NOT_REGISTERED"
            String refuse = "检测到越权或规则绕过请求（如直接下单、调用 CTP、注册交易能力）。"
                    + "Demo 不连接任何交易系统，也没有注册交易能力（错误码 "
                    + code + "）。请使用结构化流程：策略共创 → 确定性校验 → 审批 → 冻结。";
            // Surface the structured intent (no model) so the Inspector shows it.
            obs.onEvent(HarnessObserver.event("intent.detected", java.util.Map.of(
                    "runId", request.runId(),
                    "primaryIntent", IntentResult.GENERAL_QA,
                    "labels", java.util.List.of(IntentResult.GENERAL_QA),
                    "authorization", IntentResult.AUTH_NOT_CONFIRMED,
                    "requiresConfirmation", false,
                    "errorCode", code)));
            AgentRun failed = terminalRun(request, AgentRun.STATUS_FAILED, 0, 0, 0, null);
            // Stamp a deterministic error code on the run (no stack trace leak,
            // no sensitive content).
            failed = new AgentRun(failed.id(), failed.sessionId(), failed.workspaceId(),
                    IntentResult.GENERAL_QA, AgentRun.STATUS_FAILED, failed.budget(),
                    0, 0, 0, null, true, code, failed.createdAt(), java.time.Instant.now().toString());
            obs.onEvent(HarnessObserver.event("run.failed", java.util.Map.of(
                    "runId", request.runId(),
                    "status", AgentRun.STATUS_FAILED,
                    "errorCode", code)));
            return new TurnExecution(IntentResult.GENERAL_QA, null, failed,
                    java.util.List.of(), null, refuse, null, true, null, null);
        }

        // Deterministic classification FIRST.
        IntentClassifier.Classification det = IntentClassifier.classify(request.content());
        // Then the model intent port (may be unavailable).
        IntentInferencePort.IntentInferenceRequest ireq =
                new IntentInferencePort.IntentInferenceRequest(
                        request.content(),
                        request.activeDraft() != null,
                        request.activeDraft() == null ? null : request.activeDraft().id(),
                        request.activeDraft() == null ? null : request.activeDraft().status(),
                        request.pendingQuestion() == null ? null : request.pendingQuestion().field(),
                        IntentResult.ALLOWED_LABELS);
        IntentInferencePort.ModelIntent model = request.intentPort().infer(ireq);

        // Reconcile: rules take priority over the model.
        IntentReconciler.ReconcileContext rctx = new IntentReconciler.ReconcileContext(
                request.content(),
                request.activeDraft() != null,
                request.activeDraft() == null ? null : request.activeDraft().id(),
                request.activeDraft() == null ? null : request.activeDraft().status(),
                request.pendingQuestion() == null ? null : request.pendingQuestion().field());
        IntentResult intent = reconciler.reconcile(det, rctx, model);

        // intent.detected — safe structured intent only (no hidden chain).
        obs.onEvent(HarnessObserver.event("intent.detected", intentMap(intent)));

        // Budget check before proceeding.
        String budgetReason = loopGuard.checkBudget(0);
        if (budgetReason != null) {
            RunCheckpoint cp = checkpoint(request, 0, budgetReason);
            AgentRun run = terminalRun(request, AgentRun.STATUS_CHECKPOINTED, 0, 0, 0, cp.id());
            obs.onEvent(HarnessObserver.event("run.checkpointed", java.util.Map.of(
                    "runId", request.runId(), "reason", budgetReason, "checkpointId", cp.id())));
            return new TurnExecution(intent.primaryIntent(), null, run,
                    java.util.List.of(), cp, "", null, intent.modelUnavailable(), null, null);
        }

        // 第二轮 Issue #1：歧义执行短路（中文要点）。「就按这个跑一下」这类语句被
        // IntentReconciler 识别为 EXECUTION_APPLICATION（需澄清、带单一 nextQuestion）。
        // 这里【尊重 reconcile 结果】直接生成「回测/模拟/讨论」单一澄清并 COMPLETED，
        // 【不落入 planner】——否则 planner 会重新分类原始文本，要么错误追问字段、要么
        // 因为 pendingQuestion.field()=null 在 parseAnswer 处 NPE。回复只含一个问题，
        // 不创建任何 ExecutionRun/回测/交易。
        if (intent.requiresConfirmation()
                && intent.labels() != null
                && intent.labels().contains(IntentResult.EXECUTION_APPLICATION)
                && intent.nextQuestion() != null) {
            String clarify = "你说的“跑一下”含义不唯一，可能指回测、模拟或讨论。"
                    + intent.nextQuestion()
                    + "\n（Demo 不连接 SimNow，不会产生任何交易行为；回测使用本地 Mock 数据。）";
            AgentRun done = terminalRun(request, AgentRun.STATUS_COMPLETED, 1, 0,
                    clarify.length(), null);
            obs.onEvent(HarnessObserver.event("step.started", java.util.Map.of(
                    "runId", request.runId(), "seq", 0, "kind", AgentStep.KIND_QUESTION)));
            obs.onEvent(HarnessObserver.event("step.completed", java.util.Map.of(
                    "runId", request.runId(), "seq", 0, "kind", AgentStep.KIND_QUESTION,
                    "capability", "conversation.ask_one_question", "durationMs", 0)));
            obs.onEvent(HarnessObserver.event("assistant.delta", java.util.Map.of(
                    "runId", request.runId(), "nextQuestion", intent.nextQuestion())));
            obs.onEvent(HarnessObserver.event("run.completed", java.util.Map.of(
                    "runId", request.runId(), "status", AgentRun.STATUS_COMPLETED)));
            TurnQuestion q = new TurnQuestion(IntentResult.EXECUTION_APPLICATION,
                    intent.nextQuestion());
            return new TurnExecution(intent.primaryIntent(), null, done,
                    java.util.List.of(), null, clarify, q,
                    intent.modelUnavailable(), null, null, intent);
        }

        // Delegate the gated capability / budget work to the existing planner.
        // It is fully deterministic; we keep it as the single execution path so
        // there is no second Agent kernel.
        TurnExecution te = executeTurnWithBudget(request.session(), request.content(),
                request.activeDraft(), request.pendingQuestion(), request.actorRole(),
                request.budget());

        // When the reconciled intent is a strategy candidate (it may have been
        // ENRICHED here by the commodity lexicon — e.g. "黄金5日线上穿20日线
        // 买入" — which the bare classifier in the planner does not see), the
        // planner running on the raw content may produce no seed. We synthesize
        // the seed from the AUTHORITATIVE reconciled intent so the headline
        // Demo scenario works even without a model and without a ticker code.
        StrategySeed seed = te.seed();
        String assistantContent = te.assistantContent();
        List<AgentStep> steps = te.steps();
        if (seed == null
                && IntentResult.STRATEGY_CANDIDATE.equals(intent.primaryIntent())) {
            seed = synthesizeSeed(request, intent);
            // 第三轮 Issue A：策略【候选】阶段的回复只保留确定性 Seed 提示。
            // 模型的自由文本【在这里被丢弃】——因为模型并不知道"还没确认、不能追问字段、
            // 不能暗示草案已存在"。模型的文本只在普通问答（无 Seed/Draft）时才采用。
            // 这条规则保证：用户在点「继续完善策略」之前，不会被误导成"草案已存在"。
            String reply = "检测到策略候选（StrategySeed）：「" + seed.summary()
                    + "」。\n请选择：\n1. 继续完善策略（进入策略共创）\n2. 仅作为讨论（不创建草案）\n"
                    + "在创建草案之前，系统不会生成任何 Draft 或任务，也不会追问字段。";
            assistantContent = reply;
        }

        // Re-stamp the run with the server-generated runId and the reconciled
        // intent so the application layer correlates events correctly. The
        // steps already carry their own ids; we only re-link runId.
        AgentRun stamped = new AgentRun(request.runId(),
                te.run().sessionId(), te.run().workspaceId(), intent.primaryIntent(),
                te.run().status(), request.budget(), te.run().stepsUsed(),
                te.run().toolCallsUsed(), te.run().outputCharsUsed(),
                te.run().checkpointId(), te.run().modelUnavailable(),
                te.run().error(), te.run().createdAt(), te.run().finishedAt());

        // step.started / step.completed — fire one pair summarizing the run's
        // traced steps (the planner produced them deterministically). Only safe
        // summaries are emitted.
        for (AgentStep s : te.steps()) {
            obs.onEvent(HarnessObserver.event("step.started", java.util.Map.of(
                    "runId", request.runId(), "seq", s.seq(), "kind", s.kind())));
            obs.onEvent(HarnessObserver.event("step.completed", java.util.Map.of(
                    "runId", request.runId(), "seq", s.seq(), "kind", s.kind(),
                    "capability", s.capability() == null ? "" : s.capability(),
                    "durationMs", s.durationMs())));
        }
        // budget.updated.
        obs.onEvent(HarnessObserver.event("budget.updated", java.util.Map.of(
                "runId", request.runId(),
                "stepsUsed", stamped.stepsUsed(),
                "maxSteps", stamped.budget().maxSteps(),
                "toolCallsUsed", stamped.toolCallsUsed(),
                "maxToolCalls", stamped.budget().maxToolCalls(),
                "outputCharsUsed", stamped.outputCharsUsed())));

        // seed.detected / draft.updated when applicable.
        if (seed != null) {
            obs.onEvent(HarnessObserver.event("seed.detected", java.util.Map.of(
                    "runId", request.runId(),
                    "seedId", seed.id(),
                    "summary", seed.summary())));
        }
        if (te.patch() != null) {
            obs.onEvent(HarnessObserver.event("draft.updated", java.util.Map.of(
                    "runId", request.runId(),
                    "draftId", request.activeDraft() == null ? "" : request.activeDraft().id(),
                    "patchField", te.patch().toString())));
        }
        if (te.question() != null) {
            obs.onEvent(HarnessObserver.event("assistant.delta", java.util.Map.of(
                    "runId", request.runId(),
                    "nextQuestion", te.question().prompt())));
        }

        // Terminal event.
        String terminalType = switch (stamped.status()) {
            case AgentRun.STATUS_COMPLETED -> "run.completed";
            case AgentRun.STATUS_CHECKPOINTED -> "run.checkpointed";
            case AgentRun.STATUS_FAILED -> "run.failed";
            case AgentRun.STATUS_STOPPED -> "run.stopped";
            default -> "run.completed";
        };
        obs.onEvent(HarnessObserver.event(terminalType, java.util.Map.of(
                "runId", request.runId(), "status", stamped.status())));

        // Return a TurnExecution carrying the reconciled intent + re-stamped run.
        return new TurnExecution(intent.primaryIntent(), reseed(seed, request.runId()),
                stamped, steps, te.checkpoint(), assistantContent, te.question(),
                te.modelUnavailable(), te.patch(), te.parseError(), intent);
    }

    /**
     * Synthesize a StrategySeed from the AUTHORITATIVE reconciled intent. Used
     * when the reconciler enriched a Chinese-commodity idea to a candidate that
     * the bare classifier inside the planner did not recognize. The seed always
     * starts DETECTED and never creates a Draft — the user must confirm first.
     */
    private StrategySeed synthesizeSeed(HarnessTurnRequest request, IntentResult intent) {
        String now = java.time.Instant.now().toString();
        java.util.Map<String, Object> fields = intent.extractedFields();
        @SuppressWarnings("unchecked")
        java.util.List<String> instruments = fields.get("instruments") instanceof java.util.List<?> l
                ? (java.util.List<String>) l : java.util.List.of();
        String summary = summarizeContent(request.content());
        return new StrategySeed(
                java.util.UUID.randomUUID().toString(),
                summary,
                instruments,
                stringField(fields, "entryHint"),
                stringField(fields, "exitHint"),
                stringField(fields, "riskHint"),
                IntentResult.STRATEGY_CANDIDATE,
                StrategySeed.STATUS_DETECTED,
                null, now, null);
    }

    private static String stringField(java.util.Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    /** A short sanitised seed summary (≤ 120 chars). */
    private static String summarizeContent(String content) {
        String base = content == null ? "" : content.trim();
        if (base.length() > 120) base = base.substring(0, 120) + "…";
        return base;
    }

    private AgentRun terminalRun(HarnessTurnRequest req, String status, int steps,
                                 int tools, int chars, String checkpointId) {
        String now = java.time.Instant.now().toString();
        return new AgentRun(req.runId(), req.session().id(), req.session().workspaceId(),
                IntentResult.GENERAL_QA, status, req.budget(), steps, tools, chars,
                checkpointId, true, null, now, now);
    }

    private RunCheckpoint checkpoint(HarnessTurnRequest req, int stepSeq, String reason) {
        return new RunCheckpoint(java.util.UUID.randomUUID().toString(), req.runId(),
                req.session().id(), req.session().workspaceId(), stepSeq, reason,
                java.time.Instant.now().toString());
    }

    private StrategySeed reseed(StrategySeed seed, String runId) {
        if (seed == null) return null;
        // The seed is created by the deterministic planner; nothing to change.
        return seed;
    }

    private static java.util.Map<String, Object> budgetMap(RunBudget b) {
        return java.util.Map.of(
                "maxSteps", b.maxSteps(),
                "maxToolCalls", b.maxToolCalls(),
                "maxOutputChars", b.maxOutputChars());
    }

    private static java.util.Map<String, Object> intentMap(IntentResult intent) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("primaryIntent", intent.primaryIntent());
        m.put("labels", intent.labels());
        m.put("mutation", intent.mutation());
        m.put("authorization", intent.authorization());
        m.put("calibratedConfidence", intent.calibratedConfidence());
        m.put("modelUnavailable", intent.modelUnavailable());
        m.put("requiresConfirmation", intent.requiresConfirmation());
        if (intent.nextQuestion() != null) m.put("nextQuestion", intent.nextQuestion());
        return m;
    }

    /**
     * Execute one user turn through the harness: classify → (optionally gate
     * capability calls via the ToolProxy) → produce the deterministic reply +
     * at most ONE question + (optionally) a Draft patch. All steps are traced;
     * the budget is enforced (checkpoint when exhausted).
     *
     * @param session          the owning session
     * @param userContent      the validated user message
     * @param activeDraft      the CO_CREATING draft of the session (nullable)
     * @param pendingQuestion  the question the previous assistant turn asked
     *                         (nullable)
     * @param actorRole        the current actor role (RBAC for capability calls)
     * @param budget           the per-run budget (server-side; tests may shrink it)
     */
    TurnExecution executeTurnWithBudget(ConversationSession session, String userContent,
                                        StrategySpecDraft activeDraft,
                                        TurnQuestion pendingQuestion, String actorRole,
                                        RunBudget budget) {
        String now = Instant.now().toString();
        String runId = UUID.randomUUID().toString();
        String wsId = session.workspaceId();
        List<AgentStep> steps = new ArrayList<>();
        int seq = 0;

        IntentClassifier.Classification classification = IntentClassifier.classify(userContent);
        String intent = classification.intent();
        // While a Draft is being co-created, an answer to the pending single
        // question is a refinement even when the classifier sees no explicit
        // refinement words (e.g. an answer that is just "JM2609").
        boolean answerContext = activeDraft != null && pendingQuestion != null
                && !IntentClassifier.STRATEGY_CANDIDATE.equals(intent)
                && !IntentClassifier.TASK_REQUEST.equals(intent)
                && !IntentClassifier.RESEARCH.equals(intent);
        if (answerContext) {
            intent = IntentClassifier.STRATEGY_REFINEMENT;
        }
        String think = switch (intent) {
            case IntentClassifier.GENERAL_QA ->
                    "意图分类：普通问答（GENERAL_QA）。不创建任何 Draft / 任务。";
            case IntentClassifier.RESEARCH ->
                    "意图分类：研究讨论（RESEARCH）。将检索本地知识库（rag.search.local）。";
            case IntentClassifier.STRATEGY_HYPOTHESIS ->
                    "意图分类：策略假设（STRATEGY_HYPOTHESIS）。保存为研究讨论，不创建 Draft。";
            case IntentClassifier.STRATEGY_CANDIDATE ->
                    "意图分类：策略候选（STRATEGY_CANDIDATE）。生成 StrategySeed，等待用户确认，不创建 Draft。";
            case IntentClassifier.STRATEGY_REFINEMENT ->
                    "意图分类：策略完善（STRATEGY_REFINEMENT）。按上一个追问更新 Draft 字段。";
            default -> "意图分类：任务请求（TASK_REQUEST）。只说明人工门，不自动创建任务。";
        };
        steps.add(step(runId, session, seq++, AgentStep.KIND_THINK, null,
                "intent=" + intent, think, 0, now));

        StrategySeed seed = null;
        TurnQuestion question = null;
        TaskCenterDtos.DraftPatch patch = null;
        String parseError = null;
        StringBuilder output = new StringBuilder();
        int toolCalls = 0;

        // 第二轮 Issue #2：Draft 共创期间，对自由文本纠正（如「3% 是止盈，止损 1.5%」）
        // 做【确定性】提取并生成 DraftPatch，与当前 pendingQuestion 指向的字段无关。
        // 关键点：①这里只负责「提取 patch」，真正落库由上层 completeRun(...patch...) 完成
        // （见 AgentDemoStore.completeRun 的 patch 应用块）；②放在意图分类之前，确保即使
        // 回答的不是当前追问的字段也能纠正；③止盈没有独立字段，按任务要求映射进
        // exitCondition 自由文本（不新建 takeProfitPct 平行类型）。
        if (activeDraft != null
                && StrategySpecDraft.STATUS_CO_CREATING.equals(activeDraft.status())) {
            TaskCenterDtos.DraftPatch correction =
                    SpecFieldHelpers.extractCorrection(userContent);
            if (correction != null) {
                patch = correction;
                steps.add(step(runId, session, seq++, AgentStep.KIND_TOOL,
                        CapabilityRegistry.STRATEGY_DRAFT_UPDATE,
                        "field-correction", "确定性字段纠正", 0, now));
                toolCalls++;
                output.append("已根据你的说明更新策略草案（确定性校验，模型不计算数值）。");
            }
        }

        if (IntentClassifier.STRATEGY_CANDIDATE.equals(intent)) {
            // Detect the candidate: an explicit seed appears, but NO Draft is
            // created until the user chooses "进入策略共创".
            seed = new StrategySeed(UUID.randomUUID().toString(),
                    summarize(classification, userContent),
                    classification.instruments(),
                    classification.entryHint(), classification.exitHint(),
                    classification.riskHint(), intent, StrategySeed.STATUS_DETECTED,
                    null, now, null);
            String reply = "检测到策略候选（StrategySeed）：「" + seed.summary()
                    + "」。\n请选择：\n1. 继续完善策略（进入策略共创）\n2. 仅作为讨论（不创建草案）\n"
                    + "在创建草案之前，系统不会生成任何 Draft 或任务。";
            output.append(reply);
            steps.add(step(runId, session, seq++, AgentStep.KIND_ANSWER, null,
                    "seed=" + seed.summary(), reply, 0, now));
        } else if (IntentClassifier.STRATEGY_REFINEMENT.equals(intent)
                && activeDraft != null && pendingQuestion != null
                && pendingQuestion.field() != null) {
            // Apply the answer to the pending field via the gated capability.
            // A null field (e.g. left by an execution clarification) is skipped
            // here — the deterministic correction extraction above already
            // handled any field-level correction, and parseAnswer would NPE on
            // a null field.
            String field = pendingQuestion.field();
            try {
                patch = SpecFieldHelpers.parseAnswer(field, userContent);
                ToolProxy.ToolResult tr = toolProxy.execute(new ToolProxy.ToolCall(
                        CapabilityRegistry.STRATEGY_DRAFT_UPDATE,
                        Map.of("field", field, "value", ToolProxy.maskSensitive(userContent)),
                        wsId, actorRole, runId, session.id()));
                steps.add(step(runId, session, seq++, AgentStep.KIND_TOOL,
                        CapabilityRegistry.STRATEGY_DRAFT_UPDATE, tr.inputSummary(),
                        tr.ok() ? tr.output() : "DENIED: " + tr.errorCode() + " " + tr.errorMessage(),
                        tr.durationMs(), now));
                if (!tr.ok()) {
                    toolCalls++;
                    output.append("无法确认字段 ").append(field).append("：")
                          .append(tr.errorMessage());
                } else {
                    toolCalls++;
                    output.append("已记录 ").append(field)
                          .append("（该字段由确定性校验确认，模型不计算任何数值）。\n");
                }
            } catch (IllegalArgumentException e) {
                parseError = e.getMessage();
                output.append("没能从回答中解析出 ").append(field)
                      .append("：").append(e.getMessage()).append(" 请重新回答。");
            }
        } else if (IntentClassifier.RESEARCH.equals(intent)) {
            // Gated RAG call: citations or EVIDENCE_MISSING.
            ToolProxy.ToolResult tr = toolProxy.execute(new ToolProxy.ToolCall(
                    CapabilityRegistry.RAG_SEARCH_LOCAL,
                    Map.of("query", truncate(userContent, 120)),
                    wsId, actorRole, runId, session.id()));
            steps.add(step(runId, session, seq++, AgentStep.KIND_TOOL,
                    CapabilityRegistry.RAG_SEARCH_LOCAL, tr.inputSummary(),
                    tr.ok() ? tr.output() : "DENIED: " + tr.errorCode() + " " + tr.errorMessage(),
                    tr.durationMs(), now));
            toolCalls++;
            if (tr.ok()) {
                output.append("研究说明（知识来源：本地授权知识库）：\n").append(tr.output());
                if (tr.output().contains("EVIDENCE_MISSING")) {
                    output.append("\n（EVIDENCE_MISSING：本地知识不可用，不凭空补全。）");
                }
            } else {
                output.append("研究检索被拒绝：").append(tr.errorMessage());
            }
        } else if (IntentClassifier.TASK_REQUEST.equals(intent)) {
            output.append("创建回测任务需要完整的人工流程：1) 草案通过确定性校验并提交审批；"
                    + "2) OWNER/ADMIN 人工批准并冻结为不可变规格快照；3) 人工创建回测任务；"
                    + "4) 任务人工审批；5) 人工点击「启动已批准回测」。本系统不提供自动创建、"
                    + "自动审批或自动启动。");
        } else if (IntentClassifier.STRATEGY_HYPOTHESIS.equals(intent)) {
            output.append("这是一个研究假设：可以继续讨论，但当前不会创建草案或任务。");
        } else {
            // P1-1: do NOT append the stale "收到…" template unconditionally.
            // It is only a MODEL_UNAVAILABLE fallback (filled in below); when a
            // real model answer exists the user sees the model text alone, with
            // no boilerplate appended. A plain QA turn never asks a question.
            // (No output here for GENERAL_QA when a model may answer.)
        }

        // The ONE-question rule: while a Draft is being co-created, ask at
        // most the highest-impact missing field (never more than one). The
        // question advances after a successful answer (ask the NEXT field),
        // and is re-asked after a failed parse — so the assistant turn always
        // carries the live pending question for the next user turn.
        if (activeDraft != null
                && StrategySpecDraft.STATUS_CO_CREATING.equals(activeDraft.status())) {
            StrategySpecDraft effective = patch == null ? activeDraft
                    : TaskCenterStore.applyPatch(activeDraft, patch, null);
            question = SpecFieldHelpers.nextQuestion(effective);
            if (question != null) {
                steps.add(step(runId, session, seq++, AgentStep.KIND_QUESTION, null,
                        "field=" + question.field(), question.prompt(), 0, now));
                output.append("\n\n下一位待确认字段（每次只问一个最高影响字段）：")
                      .append(question.prompt());
            }
        }

        // MODEL_UNAVAILABLE handling: try the AgentScope agent ONLY when a key
        // is configured; on any error fall back to the deterministic planner,
        // clearly labeled (never a faked model answer).
        boolean modelUnavailable = !modelAvailable();
        if (!modelUnavailable) {
            try {
                String modelText = callModel(session, userContent, intent);
                if (modelText != null && !modelText.isBlank()) {
                    output.insert(0, modelText + "\n\n");
                } else {
                    modelUnavailable = true;
                }
            } catch (Throwable t) {
                log.warn("task-center model call failed, using deterministic planner: {}",
                        t.getClass().getSimpleName());
                modelUnavailable = true;
            }
        }
        if (modelUnavailable) {
            // P1-1: only when the model is genuinely unavailable do we surface a
            // clearly-labeled deterministic answer for plain QA. This is never
            // appended on top of a real model answer.
            if (output.length() == 0
                    && IntentClassifier.GENERAL_QA.equals(intent)) {
                output.append(deterministicQa(userContent));
            }
            output.append("\n\n（MODEL_UNAVAILABLE：模型未配置或调用失败。"
                    + "以上内容由本地确定性规则生成，不代表模型结论；"
                    + "确定性校验与本地回测不受影响。）");
        }

        // Budget enforcement: output chars / steps / tool calls.
        RunCheckpoint checkpoint = null;
        String status = AgentRun.STATUS_COMPLETED;
        String checkpointReason = null;
        int outputChars = output.length();
        if (!BudgetPolicy.outputCharsRemaining(budget, outputChars)) {
            checkpointReason = RunCheckpoint.REASON_MAX_OUTPUT_CHARS;
        } else if (!BudgetPolicy.stepsRemaining(budget, seq)) {
            checkpointReason = RunCheckpoint.REASON_MAX_STEPS;
        } else if (!BudgetPolicy.toolCallsRemaining(budget, toolCalls)) {
            checkpointReason = RunCheckpoint.REASON_MAX_TOOL_CALLS;
        }
        if (checkpointReason != null) {
            status = AgentRun.STATUS_CHECKPOINTED;
            checkpoint = new RunCheckpoint(UUID.randomUUID().toString(), runId,
                    session.id(), wsId, seq, checkpointReason,
                    Instant.now().toString());
        }

        AgentRun run = new AgentRun(runId, session.id(), wsId, intent, status, budget,
                seq, toolCalls, outputChars,
                checkpoint == null ? null : checkpoint.id(), modelUnavailable,
                null, now, Instant.now().toString());
        return new TurnExecution(intent, seed, run, steps, checkpoint,
                output.toString(), question, modelUnavailable, patch, parseError);
    }

    /** The deterministic QA answer (no model, clearly local). This is ONLY the
     *  MODEL_UNAVAILABLE fallback for plain QA — it is never appended on top of
     *  a real model answer, so there is no duplicated "收到…" boilerplate. */
    private String deterministicQa(String content) {
        if (content == null || content.isBlank()) {
            return "请描述你的想法或问题。";
        }
        return "（当前未配置真实模型。）关于「" + content.trim()
                + "」，我只能在本地知识范围内简要回应。"
                + "若要共创策略，请描述交易想法（标的 + 条件 + 动作），我会生成可确认的策略候选。";
    }

    /** A short sanitised seed summary (≤ {@value #MAX_SEED_SUMMARY} chars). */
    private String summarize(IntentClassifier.Classification c, String content) {
        String base = content == null ? "" : content.trim();
        if (base.length() > 120) base = base.substring(0, 120) + "…";
        return base;
    }

    /** Call the AgentScope agent with a sanitised prompt (credentials never
     *  enter the prompt). Returns the model text or null on failure. */
    private String callModel(ConversationSession session, String userContent,
                             String intent) {
        ReActAgent agent = taskCenterAgent;
        if (agent == null) return null;
        String prompt = "会话：" + session.title() + "\n意图：" + intent
                + "\n用户消息：" + ToolProxy.maskSensitive(userContent)
                + "\n请用中文简要回应（最多 200 字）。只做研究与共创说明，不输出交易指令或凭证。";
        RuntimeContext ctx = new RuntimeContext.Builder()
                .sessionId("taskcenter-" + session.id())
                .userId("local-actor").build();
        Msg userMsg = new Msg.Builder().role(MsgRole.USER).textContent(prompt).build();
        Msg reply = agent.call(List.of(userMsg), ctx).block(MODEL_CALL_TIMEOUT);
        if (reply == null) return null;
        String text = reply.getTextContent();
        return text == null ? null : text.trim();
    }

    private static AgentStep step(String runId, ConversationSession session, int seq,
                                  String kind, String capability, String input,
                                  String output, long durationMs, String now) {
        String safeOutput = ToolProxy.maskSensitive(output);
        boolean truncated = safeOutput.length() > BudgetPolicy.DEFAULT_MAX_OUTPUT_CHARS;
        if (truncated) {
            safeOutput = safeOutput.substring(0, BudgetPolicy.DEFAULT_MAX_OUTPUT_CHARS);
        }
        return new AgentStep(UUID.randomUUID().toString(), runId, session.id(),
                session.workspaceId(), seq, kind, capability,
                ToolProxy.maskSensitive(input), safeOutput, truncated, durationMs, now);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
