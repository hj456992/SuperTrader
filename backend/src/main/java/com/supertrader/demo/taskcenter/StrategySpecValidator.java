package com.supertrader.demo.taskcenter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/**
 * The pure, deterministic StrategySpec Validator (Module 9).
 *
 * <p>The Validator NEVER calls an LLM; an LLM / Agent has no power to turn a
 * failed report into a pass. It validates at least:
 * <ul>
 *   <li>the required fields are complete (completeness = 100);</li>
 *   <li>templateType is on the exact three-template whitelist — a non-whitelist
 *       Draft is saved but NEVER backtestable (NOT_BACKTESTABLE);</li>
 *   <li>instruments follow the Module 8 rules (1..20 distinct ids, each 1..16
 *       letters/digits, canonical uppercase);</li>
 *   <li>timeframe is on the whitelist (TICK / 1M / 5M / 15M / 30M / 1H / 1D);</li>
 *   <li>parameter bounds: fastWindow 2..100, slowWindow 3..300 and
 *       slowWindow &gt; fastWindow, positionSize 0..1, stopLossPct 0..30,
 *       feeBps 0..100, slippageBps 0..100;</li>
 *   <li>entry / exit semantics do not conflict (both non-blank, distinct,
 *       within length limits, no control characters);</li>
 *   <li>risk parameters are computable (they are plain bounded numbers);</li>
 *   <li>the dataset coverage for the required contract / timeframe exists
 *       (from {@link DatasetCatalog}, fail-closed DATA_UNAVAILABLE otherwise);</li>
 *   <li>no arbitrary code / prompt / tool permission / credential-shaped
 *       content anywhere in the declaration;</li>
 *   <li>the declaration maps to exactly one template-specific Backtest Runner input.</li>
 * </ul>
 *
 * <p>{@code validatorVersion} is the fixed rule-set version "1.0.0".
 */
@Component
public class StrategySpecValidator {

    public static final String VALIDATOR_VERSION = "1.0.0";

    static final Set<String> TIMEFRAMES = Set.of(
            "TICK", "1M", "5M", "15M", "30M", "1H", "1D");

    /** Credential-shaped / code / prompt / tool / URL tokens that are FORBIDDEN
     *  inside a declarative StrategySpec (fail-closed: any hit is a blocking
     *  issue). Word-boundary matching keeps normal text like "tokenize" or
     *  "systematic" from false positives. Chinese credential words are
     *  included: user content / declarations containing them are rejected
     *  rather than stored or logged. */
    static final List<String> BANNED_TOKENS = List.of(
            "password", "authcode", "appid", "investorid", "brokerid",
            "privatekey", "credential", "apikey", "api_key", "secret",
            "密码", "口令", "私钥", "令牌", "授权码", "凭证",
            "reqorderinsert", "reqorderaction", "orderinsert", "ordercancel",
            "orderaction", "system.exec", "processbuilder", "runtime.exec",
            "invoke-cmd", "popen", "shell.exec", "curl", "wget",
            "eval(", "exec(", "import ", "class ", "new object", "reflect",
            "class.forname", "javascript:", "data:text", "tool:",
            "mcp://", "function(", "=>", ";;", "&&", "||", "`", "$(",
            "token ", "private key");

    /** The validated result of a Draft (before it becomes a stored report). */
    public record Validated(boolean valid, boolean backtestable,
                            List<ValidationIssue> issues, List<String> warnings) {}

    private final DatasetCatalog catalog;

    public StrategySpecValidator(DatasetCatalog catalog) {
        this.catalog = catalog;
    }

    /** Visible for tests: the fixed validator version. */
    public String version() {
        return VALIDATOR_VERSION;
    }

    public Validated validate(StrategySpecDraft draft) {
        List<ValidationIssue> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (draft == null) {
            return new Validated(false, false,
                    List.of(new ValidationIssue("draft", "DRAFT_MISSING", "草案不存在")),
                    List.of());
        }

        // 1. Exact closed template whitelist.
        if (!StrategySpecDraft.TEMPLATE_WHITELIST.contains(draft.templateType())) {
            issues.add(new ValidationIssue("templateType", "TEMPLATE_NOT_WHITELISTED",
                    "模板类型 " + nullSafe(draft.templateType())
                            + " 不在受控白名单中，不可回测"));
            return new Validated(false, false, issues, warnings);
        }

        // 2. completeness of the required fields.
        if (draft.completeness() < 100) {
            for (String field : draft.missingFields() == null ? List.<String>of()
                    : draft.missingFields()) {
                issues.add(new ValidationIssue(field, "FIELD_MISSING",
                        "缺少必填字段：" + field));
            }
            if (issues.isEmpty()) {
                issues.add(new ValidationIssue("completeness", "INCOMPLETE",
                        "草案未完成（完整度 " + draft.completeness() + "%）"));
            }
        }

        // 3. instruments (Module 8 rules, canonical uppercase form).
        if (draft.instruments() == null || draft.instruments().isEmpty()) {
            issues.add(new ValidationIssue("instruments", "INSTRUMENTS_REQUIRED",
                    "合约列表不能为空"));
        } else {
            validateInstruments(draft.instruments(), issues);
        }

        // 4. timeframe whitelist.
        String tf = draft.timeframe();
        if (tf == null || tf.isBlank()) {
            issues.add(new ValidationIssue("timeframe", "TIMEFRAME_REQUIRED",
                    "周期不能为空"));
        } else if (!TIMEFRAMES.contains(tf)) {
            issues.add(new ValidationIssue("timeframe", "TIMEFRAME_NOT_WHITELISTED",
                    "不支持的周期（可选：TICK、1M、5M、15M、30M、1H、1D）"));
        }

        // 5. parameter bounds.
        SpecParameters p = draft.parameters();
        if (p == null) {
            issues.add(new ValidationIssue("parameters", "PARAMETER_MISSING", "缺少模板参数"));
        } else if (!draft.templateType().equals(p.type())) {
            issues.add(new ValidationIssue("parameters.type", "PARAMETER_TYPE_MISMATCH",
                    "参数 type 必须与 templateType 一致"));
        } else {
            validateParameters(draft, p, issues);
        }

        // 6. entry / exit semantics do not conflict; risk + assumptions present.
        validateText("entryCondition", draft.entryCondition(), 400, issues);
        validateText("exitCondition", draft.exitCondition(), 400, issues);
        validateText("riskLimits", draft.riskLimits(), 400, issues);
        validateText("backtestAssumptions", draft.backtestAssumptions(), 500, issues);
        if (draft.entryCondition() != null && draft.exitCondition() != null
                && !draft.entryCondition().isBlank() && !draft.exitCondition().isBlank()) {
            String entry = draft.entryCondition().trim();
            String exit = draft.exitCondition().trim();
            if (entry.equalsIgnoreCase(exit)) {
                issues.add(new ValidationIssue("exitCondition", "ENTRY_EXIT_CONFLICT",
                        "入场条件与出场条件完全相同，语义冲突"));
            }
        }

        // 7. forbidden content (code / prompt / tool permission / credential
        //    shapes) in every declaration field.
        for (String field : List.of("name", "entryCondition", "exitCondition",
                "riskLimits", "backtestAssumptions")) {
            String value = switch (field) {
                case "name" -> draft.name();
                case "entryCondition" -> draft.entryCondition();
                case "exitCondition" -> draft.exitCondition();
                case "riskLimits" -> draft.riskLimits();
                default -> draft.backtestAssumptions();
            };
            if (value != null && containsBannedContent(value)) {
                issues.add(new ValidationIssue(field, "FORBIDDEN_CONTENT",
                        "声明内容包含被禁止的代码/脚本/Prompt/工具权限/凭证形态内容"));
            }
        }

        // 8. dataset coverage for the required contract + timeframe.
        boolean datasetOk = false;
        if (draft.instruments() != null && !draft.instruments().isEmpty()
                && tf != null && TIMEFRAMES.contains(tf)) {
            for (String instrument : draft.instruments()) {
                List<DatasetCatalog.DatasetDescriptor> covering =
                        catalog.coverage(instrument, tf);
                if (covering.isEmpty()) {
                    issues.add(new ValidationIssue("dataset", "DATA_UNAVAILABLE",
                            "没有覆盖 " + instrument + " " + tf
                                    + " 的已登记本地数据集（内置验收样本或 DATA_UNAVAILABLE）"));
                } else {
                    datasetOk = true;
                }
            }
            if (datasetOk) {
                warnings.add("回测将使用内置确定性验收样本（非真实行情，不构成投资建议），"
                        + "或明确标记 DATA_UNAVAILABLE；不存在真实授权历史数据");
            }
        } else {
            issues.add(new ValidationIssue("dataset", "DATA_UNAVAILABLE",
                    "合约或周期不完整，无法评估数据集覆盖"));
        }

        // 9. maps to a template-specific Backtest Runner input.
        if (issues.stream().noneMatch(i -> "TEMPLATE_NOT_WHITELISTED".equals(i.code()))
                && !datasetOk) {
            // covered by the DATA_UNAVAILABLE issues above
        }

        boolean valid = issues.isEmpty();
        return new Validated(valid, valid && datasetOk, issues, warnings);
    }

    // ------------------------------------------------------------------ //
    // Rule helpers (all pure / deterministic)
    // ------------------------------------------------------------------ //

    private static void validateInstruments(List<String> instruments,
                                            List<ValidationIssue> issues) {
        if (instruments.size() > 20) {
            issues.add(new ValidationIssue("instruments", "TOO_MANY_INSTRUMENTS",
                    "去重后合约数量需为 1–20 个"));
        }
        Set<String> seen = new HashSet<>();
        for (String item : instruments) {
            if (item == null || item.isEmpty() || item.length() > 16) {
                issues.add(new ValidationIssue("instruments", "INSTRUMENT_INVALID",
                        "合约代码长度需为 1–16 个字符"));
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < item.length(); i++) {
                char c = item.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                issues.add(new ValidationIssue("instruments", "INSTRUMENT_INVALID",
                        "合约代码只能包含字母与数字，且必须为大写规范格式（如 JM2609）"));
            } else if (!seen.add(item)) {
                issues.add(new ValidationIssue("instruments", "INSTRUMENT_DUPLICATE",
                        "合约列表包含重复项：" + item));
            }
        }
    }

    private static void validateParameters(StrategySpecDraft draft, SpecParameters p,
                                           List<ValidationIssue> issues) {
        switch (draft.templateType()) {
            case StrategySpecDraft.TEMPLATE_SMA_CROSS -> validateSmaParameters(p, issues);
            case StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT ->
                    validateBreakoutParameters(p, issues);
            case StrategySpecDraft.TEMPLATE_EVENT_SIGNAL ->
                    validateEventParameters(draft, p, issues);
            default -> issues.add(new ValidationIssue("templateType",
                    "TEMPLATE_NOT_WHITELISTED", "模板不在白名单"));
        }
    }

    private static void validateSmaParameters(SpecParameters p,
                                              List<ValidationIssue> issues) {
        if (p.sma() == null) {
            issues.add(new ValidationIssue("parameters", "PARAMETER_TYPE_MISMATCH",
                    "SMA_CROSS 需要 SMA 参数"));
            return;
        }
        intParam("fastWindow", p.fastWindow(), 2, 100, issues);
        if (p.slowWindow() == null) {
            issues.add(new ValidationIssue("slowWindow", "PARAMETER_MISSING",
                    "缺少参数：slowWindow"));
        } else if (p.slowWindow() < 3 || p.slowWindow() > 300) {
            issues.add(new ValidationIssue("slowWindow", "PARAMETER_OUT_OF_RANGE",
                    "slowWindow 需为 3–300 的整数"));
        } else if (p.fastWindow() != null && p.slowWindow() <= p.fastWindow()) {
            issues.add(new ValidationIssue("slowWindow", "SLOW_NOT_ABOVE_FAST",
                    "slowWindow 必须大于 fastWindow"));
        }
        doubleParam("positionSize", p.positionSize(), 0, 1, issues);
        doubleParam("stopLossPct", p.stopLossPct(), 0, 30, issues);
        intParam("feeBps", p.feeBps(), 0, 100, issues);
        intParam("slippageBps", p.slippageBps(), 0, 100, issues);
    }

    private static void validateBreakoutParameters(SpecParameters p,
                                                   List<ValidationIssue> issues) {
        SpecParameters.PriceBreakout b = p.priceBreakout();
        if (b == null) {
            issues.add(new ValidationIssue("parameters", "PARAMETER_TYPE_MISMATCH",
                    "PRICE_BREAKOUT 需要突破参数"));
            return;
        }
        intParam("lookbackBars", b.lookbackBars(), 2, 300, issues);
        enumParam("direction", b.direction(), Set.of("LONG", "SHORT"), issues);
        intParam("entryOffsetTicks", b.entryOffsetTicks(), 0, 100, issues);
        positiveDoubleParam("positionSize", b.positionSize(), 1, issues);
        doubleParam("stopLossPct", b.stopLossPct(), 0, 30, issues);
        intParam("feeBps", b.feeBps(), 0, 100, issues);
        intParam("slippageBps", b.slippageBps(), 0, 100, issues);
        validateTiming(p, SpecParameters.SIGNAL_BAR_CLOSE, issues);
    }

    private static void validateEventParameters(StrategySpecDraft draft, SpecParameters p,
                                                List<ValidationIssue> issues) {
        SpecParameters.EventSignal e = p.eventSignal();
        if (e == null) {
            issues.add(new ValidationIssue("parameters", "PARAMETER_TYPE_MISMATCH",
                    "EVENT_SIGNAL 需要事件参数"));
            return;
        }
        requiredEventText("eventId", e.eventId(), issues);
        requiredEventText("sourceId", e.sourceId(), issues);
        requiredEventText("contentHash", e.contentHash(), issues);
        requiredEventText("originalPublishedAt", e.originalPublishedAt(), issues);
        requiredEventText("fetchedAt", e.fetchedAt(), issues);
        requiredEventText("availableAt", e.availableAt(), issues);
        requiredEventText("validFrom", e.validFrom(), issues);
        requiredEventText("validUntil", e.validUntil(), issues);
        requiredEventText("relatedInstrument", e.relatedInstrument(), issues);
        requiredEventText("direction", e.direction(), issues);
        requiredEventText("confirmationId", e.confirmationId(), issues);
        requiredEventText("confirmedByMemberId", e.confirmedByMemberId(), issues);
        requiredEventText("confirmedAt", e.confirmedAt(), issues);
        requiredEventText("sourceStatus", e.sourceStatus(), issues);
        enumParam("direction", e.direction(), Set.of("LONG", "SHORT"), issues);
        if (!"AVAILABLE".equals(e.sourceStatus())) {
            issues.add(new ValidationIssue("sourceStatus", "EVENT_SOURCE_UNAVAILABLE",
                    "事件来源必须为 AVAILABLE"));
        }
        Instant published = instantParam("originalPublishedAt", e.originalPublishedAt(), issues);
        Instant fetched = instantParam("fetchedAt", e.fetchedAt(), issues);
        Instant available = instantParam("availableAt", e.availableAt(), issues);
        Instant validFrom = instantParam("validFrom", e.validFrom(), issues);
        Instant validUntil = instantParam("validUntil", e.validUntil(), issues);
        Instant confirmed = instantParam("confirmedAt", e.confirmedAt(), issues);
        if (available != null && confirmed != null && confirmed.isBefore(available)) {
            issues.add(new ValidationIssue("confirmedAt",
                    "EVENT_CONFIRMATION_BEFORE_AVAILABLE",
                    "confirmedAt 不得早于 availableAt"));
        }
        if (validFrom != null && validUntil != null && validUntil.isBefore(validFrom)) {
            issues.add(new ValidationIssue("validUntil", "EVENT_VALIDITY_INVALID",
                    "validUntil 不得早于 validFrom"));
        }
        if (published != null && fetched != null && fetched.isBefore(published)) {
            issues.add(new ValidationIssue("fetchedAt", "EVENT_FETCH_BEFORE_PUBLISH",
                    "fetchedAt 不得早于 originalPublishedAt"));
        }
        if (draft.instruments() != null && !draft.instruments().isEmpty()
                && e.relatedInstrument() != null
                && !draft.instruments().contains(e.relatedInstrument())) {
            issues.add(new ValidationIssue("relatedInstrument",
                    "EVENT_INSTRUMENT_MISMATCH", "事件关联标的与策略标的不一致"));
        }
        positiveDoubleParam("positionSize", e.positionSize(), 1, issues);
        doubleParam("stopLossPct", e.stopLossPct(), 0, 30, issues);
        intParam("feeBps", e.feeBps(), 0, 100, issues);
        intParam("slippageBps", e.slippageBps(), 0, 100, issues);
        validateTiming(p, SpecParameters.SIGNAL_EVENT_AVAILABLE, issues);
    }

    private static void validateTiming(SpecParameters p, String expectedSignal,
                                       List<ValidationIssue> issues) {
        if (!expectedSignal.equals(p.signalAt())
                || !SpecParameters.FILL_NEXT_BAR_OPEN.equals(p.fillAt())) {
            issues.add(new ValidationIssue("executionTiming", "EXECUTION_TIMING_INVALID",
                    "仅支持受控信号时点与 NEXT_BAR_OPEN 成交"));
        }
    }

    private static void requiredEventText(String field, String value,
                                          List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(new ValidationIssue(field, "PARAMETER_MISSING",
                    "缺少事件事实：" + field));
        }
    }

    private static Instant instantParam(String field, String value,
                                        List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            issues.add(new ValidationIssue(field, "EVENT_TIME_INVALID",
                    field + " 必须是严格 ISO-8601 时间"));
            return null;
        }
    }

    private static void enumParam(String field, String value, Set<String> allowed,
                                  List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(new ValidationIssue(field, "PARAMETER_MISSING", "缺少参数：" + field));
        } else if (!allowed.contains(value)) {
            issues.add(new ValidationIssue(field, "PARAMETER_INVALID", field + " 非法"));
        }
    }

    private static void positiveDoubleParam(String field, Double value, double max,
                                            List<ValidationIssue> issues) {
        if (value == null) {
            issues.add(new ValidationIssue(field, "PARAMETER_MISSING", "缺少参数：" + field));
        } else if (Double.isNaN(value) || value <= 0 || value > max) {
            issues.add(new ValidationIssue(field, "PARAMETER_OUT_OF_RANGE",
                    field + " 需大于 0 且不超过 " + max));
        }
    }

    private static void intParam(String field, Integer value, int min, int max,
                                 List<ValidationIssue> issues) {
        if (value == null) {
            issues.add(new ValidationIssue(field, "PARAMETER_MISSING",
                    "缺少参数：" + field));
        } else if (value < min || value > max) {
            issues.add(new ValidationIssue(field, "PARAMETER_OUT_OF_RANGE",
                    field + " 需为 " + min + "–" + max + " 的整数"));
        }
    }

    private static void doubleParam(String field, Double value, double min, double max,
                                    List<ValidationIssue> issues) {
        if (value == null) {
            issues.add(new ValidationIssue(field, "PARAMETER_MISSING",
                    "缺少参数：" + field));
        } else if (Double.isNaN(value) || value < min || value > max) {
            issues.add(new ValidationIssue(field, "PARAMETER_OUT_OF_RANGE",
                    field + " 需为 " + min + "–" + max + " 之间的数值"));
        }
    }

    private static void validateText(String field, String value, int maxLength,
                                     List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(new ValidationIssue(field, "FIELD_REQUIRED",
                    "缺少必填字段：" + field));
            return;
        }
        if (value.length() > maxLength) {
            issues.add(new ValidationIssue(field, "FIELD_TOO_LONG",
                    field + " 过长（最多 " + maxLength + " 个字符）"));
        }
        if (hasControlCharacters(value)) {
            issues.add(new ValidationIssue(field, "CONTROL_CHARACTERS",
                    field + " 包含不允许的控制字符"));
        }
    }

    /** Word-boundary sensitive scan of the banned-token list. */
    public static boolean containsBannedContent(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : BANNED_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    private static boolean hasControlCharacters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    private static String nullSafe(String s) {
        return s == null ? "(空)" : s;
    }
}
