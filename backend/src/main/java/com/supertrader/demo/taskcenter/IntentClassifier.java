package com.supertrader.demo.taskcenter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DETERMINISTIC intent router (Module 9). It classifies a user message
 * into the multi-label intent vocabulary:
 * GENERAL_QA / RESEARCH / STRATEGY_HYPOTHESIS / STRATEGY_CANDIDATE /
 * STRATEGY_REFINEMENT / TASK_REQUEST.
 *
 * <p>The router is pure rule-based Java (no LLM): tests are offline and
 * repeatable. Detection NEVER creates a Draft — a candidate only produces a
 * StrategySeed that waits for the user's explicit choice.
 */
public final class IntentClassifier {

    private IntentClassifier() {}

    public static final String GENERAL_QA = "GENERAL_QA";
    public static final String RESEARCH = "RESEARCH";
    public static final String STRATEGY_HYPOTHESIS = "STRATEGY_HYPOTHESIS";
    public static final String STRATEGY_CANDIDATE = "STRATEGY_CANDIDATE";
    public static final String STRATEGY_REFINEMENT = "STRATEGY_REFINEMENT";
    public static final String TASK_REQUEST = "TASK_REQUEST";

    private static final Pattern INSTRUMENT = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Z]{1,3}\\d{3,4}(?![A-Za-z0-9])");
    private static final Pattern TRADING_VERB = Pattern.compile(
            "买入|卖出|做多|做空|开仓|平仓|入场|出场|金叉|死叉|突破|上穿|下穿|挂单|触发");
    private static final Pattern CONDITION_WORD = Pattern.compile(
            "如果|当|一旦|条件|时|只要|每逢|若|等到");
    private static final Pattern RESEARCH_WORD = Pattern.compile(
            "为什么|什么|如何|是否|分析|研究|影响|原因|对比|数据|历史|统计|概念|理解|解释|说明|背景|收益|回测结果");
    private static final Pattern TASK_WORD = Pattern.compile(
            "创建回测|运行回测|启动回测|执行回测|提交回测|回测一下|跑一下回测|发起回测");
    private static final Pattern REFINEMENT_WORD = Pattern.compile(
            "止损|仓位|窗口|快线|慢线|手续费|滑点|周期|改成|调整为|设为|参数");
    private static final Pattern HAS_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** The classifier output for one message. */
    public record Classification(String intent, List<String> instruments,
                                 String entryHint, String exitHint, String riskHint) {}

    public static Classification classify(String content) {
        String text = content == null ? "" : content.trim();
        List<String> instruments = SpecFieldHelpers.extractInstruments(text);
        Matcher verb = TRADING_VERB.matcher(text);
        boolean hasVerb = verb.find();
        Matcher cond = CONDITION_WORD.matcher(text);
        boolean hasCondition = cond.find();
        Matcher task = TASK_WORD.matcher(text);
        boolean hasTask = task.find();
        Matcher research = RESEARCH_WORD.matcher(text);
        boolean hasResearch = research.find();
        Matcher refine = REFINEMENT_WORD.matcher(text);
        boolean hasRefinement = refine.find();
        boolean hasNumber = HAS_NUMBER.matcher(text).find();

        // A TASK_REQUEST is an explicit request to create/run a backtest —
        // the service only explains the manual gates; it never auto-creates.
        if (hasTask) {
            return new Classification(TASK_REQUEST, instruments, null, null, null);
        }
        // A strategy candidate needs observable condition + instrument +
        // trading action (the PRD minimum).
        if (!instruments.isEmpty() && hasVerb && hasCondition) {
            return new Classification(STRATEGY_CANDIDATE, instruments,
                    hintOf(text, "买入|做多|开仓|入场|上穿|金叉|突破"),
                    hintOf(text, "卖出|做空|平仓|出场|下穿|死叉"),
                    hintOf(text, "止损|仓位|风控|风险|比例|回撤"));
        }
        // A refinement message answers the current co-creation question
        // (parameter / condition / period values).
        if (hasRefinement && (hasNumber || !instruments.isEmpty())) {
            return new Classification(STRATEGY_REFINEMENT, instruments, null, null, null);
        }
        // A hypothesis is a conditional market idea without a tradable action.
        if (hasCondition && hasResearch) {
            return new Classification(STRATEGY_HYPOTHESIS, instruments, null, null, null);
        }
        if (hasResearch || text.endsWith("？") || text.endsWith("?")) {
            return new Classification(RESEARCH, instruments, null, null, null);
        }
        return new Classification(GENERAL_QA, instruments, null, null, null);
    }

    /** A short deterministic hint: the matched phrase or its sentence. */
    private static String hintOf(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        if (!m.find()) return null;
        int start = Math.max(0, m.start() - 12);
        int end = Math.min(text.length(), m.end() + 12);
        String hint = text.substring(start, end).trim();
        if (hint.length() > SpecFieldHelpers.MAX_TEXT) {
            hint = hint.substring(0, SpecFieldHelpers.MAX_TEXT);
        }
        return hint.isEmpty() ? null : hint;
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
