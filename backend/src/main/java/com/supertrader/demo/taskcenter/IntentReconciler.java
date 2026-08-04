package com.supertrader.demo.taskcenter;

import com.supertrader.demo.taskcenter.IntentInferencePort.ModelIntent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The deterministic intent reconciler (Task 2 / design §6.2).
 *
 * <p>It merges three signals into one {@link IntentResult}:
 * <ol>
 *   <li>the raw user content (observable text);</li>
 *   <li>the deterministic {@link IntentClassifier.Classification} (rules) plus
 *       this reconciler's own deterministic enrichment (Chinese commodity
 *       names, crossover conditions);</li>
 *   <li>the session / draft {@link ReconcileContext};</li>
 *   <li>the optional model {@link ModelIntent}.</li>
 * </ol>
 *
 * <p>Hard guarantees (fail-closed):
 * <ul>
 *   <li><b>Rules take priority.</b> A model label/mutation that contradicts a
 *       deterministic rule is dropped.</li>
 *   <li><b>The model can NEVER authorize a high-impact action.</b> Any model
 *       {@code AUTH_CONFIRMED} on an execution / capability / live mutation is
 *       clamped to {@code NOT_CONFIRMED} with {@code requiresConfirmation}.</li>
 *   <li><b>No execution mutation is ever produced.</b> {@code EXECUTE},
 *       capability registration, budget expansion and rule bypass are
 *       normalized to {@code MUTATION_NONE}.</li>
 *   <li><b>At most one {@code nextQuestion}.</b></li>
 *   <li><b>High-impact / ambiguous execution statements always ask one
 *       clarifying question</b> and never create an execution task.</li>
 * </ul>
 *
 * <p>This class is pure (no I/O, no Spring) so it is fully unit-testable.
 */
public final class IntentReconciler {

    /** Confidence thresholds (design §6.2). */
    static final double AUTO_ROUTE_THRESHOLD = 0.85;
    static final double ASK_THRESHOLD = 0.60;

    /** Phrases that signal an ambiguous execution / backtest application. */
    private static final Pattern AMBIGUOUS_RUN = Pattern.compile(
            "跑一下|按这个跑|直接执行|启动一下|跑一轮|运行一下|就跑|跑个回测|跑一次|回测一下|跑一下回测");

    /** Phrases that signal a capability-expansion / rule-bypass attempt. */
    private static final Pattern CAPABILITY_BYPASS = Pattern.compile(
            "(?i)忽略规则|绕过规则|直接调用ctp|直接下单|注册.{0,6}能力|扩大预算|"
                    + "扩大.{0,6}步|提升权限|解锁权限|注册新能力|调用ctp|ctp下单|实盘下单|"
                    + "连接ctp|接入ctp|实盘交易");

    /**
     * Public deterministic predicate: does this user content attempt a
     * capability bypass / direct-trading request (e.g. "忽略规则直接调用CTP
     * 下单")? Used by the harness to pre-route such requests to a stable
     * {@code CAPABILITY_NOT_REGISTERED} refusal WITHOUT calling the model or
     * any Tool. This is pure string matching — it never imports CTP / Order /
     * Gateway types.
     */
    public static boolean isCapabilityBypass(String content) {
        if (content == null || content.isBlank()) return false;
        return CAPABILITY_BYPASS.matcher(content).find();
    }

    /** Trading verbs (mirrors IntentClassifier + 上穿/下穿/金叉/死叉/突破). */
    private static final Pattern TRADING_VERB = Pattern.compile(
            "买入|卖出|做多|做空|开仓|平仓|入场|出场|金叉|死叉|突破|上穿|下穿|挂单|触发");

    /** Crossover / condition phrases that imply a tradable trigger. */
    private static final Pattern CROSSOVER = Pattern.compile(
            "上穿|下穿|金叉|死叉|突破|跌破|站上|跌破");

    /** Condition words (mirrors IntentClassifier). */
    private static final Pattern CONDITION_WORD = Pattern.compile(
            "如果|当|一旦|条件|时|只要|每逢|若|等到");

    /** Well-known Chinese futures commodity names (deterministic enrichment so
     *  the headline Demo scenario "黄金5日线上穿20日线买入" produces a Seed
     *  even when the model is unavailable). */
    private static final Pattern COMMODITY = Pattern.compile(
            "黄金|白银|原油|燃料油|沥青|螺纹钢|螺纹|热卷|铁矿石|铁矿|豆粕|豆油|豆一|豆二|"
                    + "棕榈油|玉米|淀粉|甲醇|纯碱|玻璃|铜|铝|锌|镍|锡|铅|pta|橡胶|焦煤|焦炭|"
                    + "动力煤|鸡蛋|生猪|苹果|红枣|棉花|棉纱|白糖|pp|pvc|塑料|硅铁|锰硅|不锈钢");

    /** Ticker-style instrument (mirrors IntentClassifier.INSTRUMENT). */
    private static final Pattern TICKER = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Z]{1,3}\\d{3,4}(?![A-Za-z0-9])");

    /** SMA crossover windows: "5日线上穿20日线", "5均线上穿20均线", "快线5上穿慢线20". */
    private static final Pattern SMA_WINDOWS = Pattern.compile(
            "(\\d+)\\s*(?:日|均线?|ma|MA)\\s*(?:线)?\\s*(?:上穿|下穿|金叉|死叉|突破)\\s*(\\d+)\\s*(?:日|均线?|ma|MA)?");

    /** Stop-loss percentage: "止损3%", "止损 3.5%", "止损3个点". */
    private static final Pattern STOP_LOSS = Pattern.compile(
            "止损\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|个百分点|个点)?");

    /** The session / draft context the reconciler merges against. */
    public record ReconcileContext(
            String content,
            boolean hasActiveDraft,
            String activeDraftId,
            String activeDraftStatus,
            String pendingField) {}

    /**
     * Reconcile one turn. The deterministic classification and the context
     * take priority; the model only enriches within allowed bounds.
     */
    public IntentResult reconcile(IntentClassifier.Classification det,
                                  ReconcileContext ctx, ModelIntent model) {
        return reconcileInternal(det, ctx, model, false);
    }

    /**
     * Reconcile a model whose labels may be arbitrary (used to assert that
     * unknown / refused mutations are dropped). Production merges always go
     * through {@link #reconcile}; this seam exists so the model-can-never-
     * escalate guarantee has a direct test.
     */
    public IntentResult reconcilerForArbitraryModel(IntentClassifier.Classification det,
                                                    ReconcileContext ctx, ModelIntent model) {
        return reconcileInternal(det, ctx, model, true);
    }

    private IntentResult reconcileInternal(IntentClassifier.Classification det,
                                           ReconcileContext ctx, ModelIntent model,
                                           boolean allowArbitraryModelLabels) {
        String content = ctx == null ? "" : ctx.content() == null ? "" : ctx.content();
        String detIntent = det.intent();

        // Enriched deterministic instruments: the classifier's tickers PLUS the
        // reconciler's commodity lexicon (so Chinese commodity ideas are
        // recognized without depending on a model).
        List<String> instruments = new ArrayList<>(det.instruments());
        for (String c : matchAll(COMMODITY, content)) {
            if (!instruments.contains(c)) instruments.add(c);
        }

        boolean hasVerb = TRADING_VERB.matcher(content).find();
        boolean hasCrossover = CROSSOVER.matcher(content).find();
        boolean hasCondition = CONDITION_WORD.matcher(content).find();
        boolean enrichedCandidate = !instruments.isEmpty()
                && hasVerb && (hasCondition || hasCrossover);
        // A deterministic candidate takes precedence over the classifier's
        // weaker labels when the enrichment recognizes a tradable idea.
        if (enrichedCandidate && !IntentClassifier.STRATEGY_CANDIDATE.equals(detIntent)) {
            detIntent = IntentClassifier.STRATEGY_CANDIDATE;
        }

        // While a Draft is being co-created, a short answer to the pending
        // single question is a refinement — UNLESS the content is clearly an
        // execution / bypass phrase (those must not be reinterpreted as field
        // answers).
        boolean ambiguousRunHere = AMBIGUOUS_RUN.matcher(content).find();
        boolean bypassHere = CAPABILITY_BYPASS.matcher(content).find();
        boolean answerContext = ctx != null && ctx.hasActiveDraft()
                && ctx.pendingField() != null
                && !IntentClassifier.STRATEGY_CANDIDATE.equals(detIntent)
                && !IntentClassifier.TASK_REQUEST.equals(detIntent)
                && !IntentClassifier.RESEARCH.equals(detIntent)
                && !ambiguousRunHere && !bypassHere;
        if (answerContext) {
            detIntent = IntentClassifier.STRATEGY_REFINEMENT;
        }

        boolean modelUnavailable = model == null || model.modelUnavailable();
        IntentResult modelResult = (model == null || model.modelUnavailable()
                || model.result() == null) ? null : model.result();

        Set<String> labels = new LinkedHashSet<>();
        Map<String, Object> extracted = new LinkedHashMap<>();
        List<String> ambiguities = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        boolean requiresConfirmation = false;
        String nextQuestion = null;

        if (modelResult != null && modelResult.labels() != null) {
            for (String label : modelResult.labels()) {
                if (allowArbitraryModelLabels || IntentResult.ALLOWED_LABELS.contains(label)) {
                    labels.add(label);
                }
            }
            if (modelResult.extractedFields() != null) {
                mergeExtracted(extracted, modelResult.extractedFields());
            }
            if (modelResult.ambiguities() != null) {
                ambiguities.addAll(modelResult.ambiguities());
            }
            if (modelResult.evidenceRefs() != null) {
                evidenceRefs.addAll(modelResult.evidenceRefs());
            }
        }
        if (!instruments.isEmpty()) {
            extracted.put("instruments", instruments);
        }
        if (det.entryHint() != null) extracted.put("entryHint", det.entryHint());
        if (det.exitHint() != null) extracted.put("exitHint", det.exitHint());
        if (det.riskHint() != null) extracted.put("riskHint", det.riskHint());
        // Deterministic scalar extraction for the headline Demo scenario
        // (works offline, no model): "5日线上穿20日线" → fastWindow=5 /
        // slowWindow=20; "止损3%" → stopLossPct=3. These let the confirmed
        // Seed carry real parameters into the Draft so completeness is > 0.
        extractSmaWindows(content, extracted);
        extractStopLoss(content, extracted);

        double modelConfidence = modelResult == null ? 0.0
                : clamp01(modelResult.modelConfidence());

        String mutation = IntentResult.MUTATION_NONE;
        String authorization = IntentResult.AUTH_NOT_REQUIRED;
        String speechAct = IntentResult.SPEECH_REQUEST;
        String domain = IntentResult.DOMAIN_GENERAL;
        String targetType = null;
        String targetId = null;

        // 1. Capability expansion / rule bypass → refuse, never execute.
        if (bypassHere || modelBypass(modelResult)) {
            return refuse(extracted, ambiguities, evidenceRefs,
                    "检测到越权或规则绕过请求（如直接下单、注册能力、扩大预算、接入交易系统），已拒绝。"
                            + "Demo 不连接任何交易系统，也不会注册交易能力。",
                    "请使用结构化流程：策略共创 → 确定性校验 → 审批 → 冻结。"
                            + "（Demo 不连接 SimNow，不会产生交易行为。）");
        }

        // 2. Ambiguous execution / backtest application → clarify, never
        //    create an execution task.
        boolean ambiguousRun = ambiguousRunHere
                || IntentClassifier.TASK_REQUEST.equals(detIntent)
                || modelExecutionLabel(modelResult);
        if (ambiguousRun) {
            labels.add(IntentResult.EXECUTION_APPLICATION);
            labels.add(IntentResult.BACKTEST_REQUEST);
            mutation = IntentResult.MUTATION_CLARIFY;
            authorization = IntentResult.AUTH_NOT_CONFIRMED;
            requiresConfirmation = true;
            domain = IntentResult.DOMAIN_EXECUTION;
            targetType = IntentResult.TARGET_STRATEGY_DRAFT;
            targetId = ctx != null ? ctx.activeDraftId() : null;
            ambiguities.add("“跑一下/执行”可能指回测、模拟或讨论，无法唯一确定对象与动作。");
            nextQuestion = pickExecutionClarification(ctx);
            return build(detIntent, labels, speechAct, domain, targetType, targetId,
                    mutation, authorization, modelConfidence, extracted, ambiguities,
                    evidenceRefs, requiresConfirmation, nextQuestion, modelUnavailable);
        }

        // 3. Strategy candidate → create a reversible Seed (waits for user).
        if (IntentClassifier.STRATEGY_CANDIDATE.equals(detIntent)) {
            labels.add(IntentResult.STRATEGY_CANDIDATE);
            mutation = IntentResult.MUTATION_CREATE_SEED;
            authorization = IntentResult.AUTH_NOT_CONFIRMED;
            requiresConfirmation = true;
            domain = IntentResult.DOMAIN_STRATEGY;
            targetType = IntentResult.TARGET_STRATEGY_SEED;
            return build(detIntent, labels, speechAct, domain, targetType, targetId,
                    mutation, authorization, modelConfidence, extracted, ambiguities,
                    evidenceRefs, requiresConfirmation, nextQuestion, modelUnavailable);
        }

        // 4. Refinement of an active Draft field.
        if (IntentClassifier.STRATEGY_REFINEMENT.equals(detIntent) && ctx != null
                && ctx.hasActiveDraft()) {
            labels.add(IntentResult.STRATEGY_REFINEMENT);
            mutation = IntentResult.MUTATION_UPDATE_FIELD;
            authorization = IntentResult.AUTH_NOT_REQUIRED;
            domain = IntentResult.DOMAIN_STRATEGY;
            targetType = IntentResult.TARGET_STRATEGY_DRAFT;
            targetId = ctx.activeDraftId();
            return build(detIntent, labels, speechAct, domain, targetType, targetId,
                    mutation, authorization, modelConfidence, extracted, ambiguities,
                    evidenceRefs, requiresConfirmation, nextQuestion, modelUnavailable);
        }

        // 5. Hypothesis / research.
        if (IntentClassifier.STRATEGY_HYPOTHESIS.equals(detIntent)) {
            labels.add(IntentResult.STRATEGY_HYPOTHESIS);
            labels.add(IntentResult.RESEARCH);
            domain = IntentResult.DOMAIN_RESEARCH;
            return build(detIntent, labels, speechAct, domain, targetType, targetId,
                    mutation, authorization, modelConfidence, extracted, ambiguities,
                    evidenceRefs, requiresConfirmation, nextQuestion, modelUnavailable);
        }
        if (IntentClassifier.RESEARCH.equals(detIntent)) {
            labels.add(IntentResult.RESEARCH);
            domain = IntentResult.DOMAIN_RESEARCH;
            return build(detIntent, labels, speechAct, domain, targetType, targetId,
                    mutation, authorization, modelConfidence, extracted, ambiguities,
                    evidenceRefs, requiresConfirmation, nextQuestion, modelUnavailable);
        }

        // 6. Low-confidence model candidate with no rule backing stays a
        //    discussion (no strategy object). Drop the CANDIDATE label so the
        //    primary intent cannot accidentally trigger Seed creation.
        if (modelResult != null && modelResult.labels() != null
                && modelResult.labels().contains(IntentResult.STRATEGY_CANDIDATE)
                && calibrate(modelConfidence) < ASK_THRESHOLD) {
            labels.remove(IntentResult.STRATEGY_CANDIDATE);
            labels.add(IntentResult.STRATEGY_HYPOTHESIS);
            requiresConfirmation = true;
            ambiguities.add("模型置信度不足，暂作为讨论，不创建策略对象。");
            nextQuestion = "你想把它作为可共创的策略候选，还是仅作为研究讨论？";
            return build(detIntent, labels, speechAct, IntentResult.DOMAIN_STRATEGY,
                    targetType, targetId, mutation, authorization, modelConfidence,
                    extracted, ambiguities, evidenceRefs, requiresConfirmation,
                    nextQuestion, modelUnavailable);
        }

        // 7. Default: ordinary QA — no strategy object, no confirmation.
        labels.add(IntentResult.GENERAL_QA);
        return build(detIntent, labels, speechAct, domain, targetType, targetId,
                mutation, authorization, modelConfidence, extracted, ambiguities,
                evidenceRefs, requiresConfirmation, nextQuestion, modelUnavailable);
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private IntentResult build(String detIntent, Set<String> labels, String speechAct,
                               String domain, String targetType, String targetId,
                               String mutation, String authorization, double modelConfidence,
                               Map<String, Object> extracted, List<String> ambiguities,
                               List<String> evidenceRefs, boolean requiresConfirmation,
                               String nextQuestion, boolean modelUnavailable) {
        String primary = pickPrimary(labels, detIntent);
        double cal = calibrate(modelConfidence);
        if (IntentResult.REFUSED_MUTATIONS.contains(mutation)) {
            mutation = IntentResult.MUTATION_NONE;
            requiresConfirmation = true;
        }
        return new IntentResult(
                primary,
                List.copyOf(labels),
                speechAct,
                domain,
                targetType,
                targetId,
                mutation,
                authorization,
                modelUnavailable ? 0.0 : modelConfidence,
                modelUnavailable ? 0.0 : cal,
                Map.copyOf(extracted),
                List.copyOf(ambiguities),
                List.copyOf(evidenceRefs),
                requiresConfirmation,
                nextQuestion,
                modelUnavailable);
    }

    private IntentResult refuse(Map<String, Object> extracted, List<String> ambiguities,
                                List<String> evidenceRefs, String ambiguity, String nextQuestion) {
        ambiguities.add(ambiguity);
        Set<String> labels = new LinkedHashSet<>();
        labels.add(IntentResult.GENERAL_QA);
        return new IntentResult(
                IntentResult.GENERAL_QA,
                List.copyOf(labels),
                IntentResult.SPEECH_REQUEST,
                IntentResult.DOMAIN_SYSTEM,
                null, null,
                IntentResult.MUTATION_NONE,
                IntentResult.AUTH_NOT_CONFIRMED,
                0.0, 0.0,
                Map.copyOf(extracted),
                List.copyOf(ambiguities),
                List.copyOf(evidenceRefs),
                true,
                nextQuestion,
                false);
    }

    private String pickPrimary(Set<String> labels, String detIntent) {
        if (labels.isEmpty()) {
            return IntentResult.GENERAL_QA;
        }
        for (String pref : List.of(IntentResult.EXECUTION_APPLICATION,
                IntentResult.STRATEGY_CANDIDATE, IntentResult.STRATEGY_REFINEMENT,
                IntentResult.STRATEGY_HYPOTHESIS, IntentResult.RESEARCH,
                IntentResult.MARKET_QUERY, IntentResult.GENERAL_QA)) {
            if (labels.contains(pref)) {
                return pref;
            }
        }
        return labels.iterator().next();
    }

    private String pickExecutionClarification(ReconcileContext ctx) {
        if (ctx != null && ctx.hasActiveDraft()) {
            return "你说的“跑一下”想做什么：在 Mock 历史数据上回测这个草案，"
                    + "还是仅作为讨论？（Demo 不会产生真实交易行为。）";
        }
        return "你想对哪个对象做什么动作？请明确：回测某个已冻结策略 / 继续共创草案 / 仅讨论。"
                + "（Demo 不连接 SimNow，不会产生交易行为。）";
    }

    private boolean modelBypass(IntentResult modelResult) {
        if (modelResult == null) return false;
        String mm = modelResult.mutation();
        if (mm != null && IntentResult.REFUSED_MUTATIONS.contains(mm)) {
            return true;
        }
        List<String> ml = modelResult.labels();
        return ml != null && (ml.contains("CAPABILITY_REGISTER") || ml.contains("BUDGET_EXPAND"));
    }

    private boolean modelExecutionLabel(IntentResult modelResult) {
        if (modelResult == null || modelResult.labels() == null) return false;
        return modelResult.labels().contains(IntentResult.EXECUTION_APPLICATION)
                || modelResult.labels().contains(IntentResult.BACKTEST_REQUEST);
    }

    private void mergeExtracted(Map<String, Object> into, Map<String, Object> from) {
        for (Map.Entry<String, Object> e : from.entrySet()) {
            if (e.getValue() != null) {
                into.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    private static List<String> matchAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = p.matcher(text);
        while (m.find()) {
            String g = m.group();
            if (!out.contains(g)) out.add(g);
        }
        return out;
    }

    /** Deterministic SMA window extraction into {@code fastWindow/slowWindow}. */
    private static void extractSmaWindows(String content, java.util.Map<String, Object> extracted) {
        if (content == null) return;
        java.util.regex.Matcher m = SMA_WINDOWS.matcher(content);
        if (m.find()) {
            try {
                int fast = Integer.parseInt(m.group(1));
                int slow = Integer.parseInt(m.group(2));
                if (fast > 0 && slow > 0 && fast < slow) {
                    extracted.putIfAbsent("fastWindow", fast);
                    extracted.putIfAbsent("slowWindow", slow);
                }
            } catch (NumberFormatException ignore) {
                // not a clean integer; skip
            }
        }
    }

    /** Deterministic stop-loss extraction into {@code stopLossPct} (0..100). */
    private static void extractStopLoss(String content, java.util.Map<String, Object> extracted) {
        if (content == null) return;
        java.util.regex.Matcher m = STOP_LOSS.matcher(content);
        if (m.find()) {
            try {
                double pct = Double.parseDouble(m.group(1));
                if (pct > 0 && pct <= 100) {
                    extracted.putIfAbsent("stopLossPct", pct);
                }
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
    }

    private double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private double calibrate(double modelConfidence) {
        // Calibrated confidence is slightly lower than the raw model number
        // (design §6.1). Never exceeds the raw number; never authorizes a
        // high-impact action.
        return clamp01(modelConfidence) * 0.9;
    }
}
