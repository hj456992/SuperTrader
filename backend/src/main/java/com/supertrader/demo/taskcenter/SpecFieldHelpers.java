package com.supertrader.demo.taskcenter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic field logic of the StrategySpec Draft (Module 9):
 * completeness / missing-fields computation, the single highest-impact
 * question selection and the parsing of a user answer into a validated field
 * patch. Pure static helpers — no I/O, no LLM, fully unit-testable.
 */
public final class SpecFieldHelpers {

    private SpecFieldHelpers() {}

    /** The question priority: the highest-impact missing field comes first. */
    static final List<String> QUESTION_ORDER = List.of(
            "instruments", "timeframe", "entryCondition", "exitCondition",
            "riskLimits", "fastWindow", "slowWindow", "positionSize",
            "stopLossPct", "feeBps", "slippageBps", "backtestAssumptions", "name");

    static final Map<String, String> QUESTION_PROMPTS = new LinkedHashMap<>();
    static {
        QUESTION_PROMPTS.put("instruments",
                "请确认策略合约代码（如 JM2609）：希望交易哪些合约？（可回答多个，用逗号分隔）");
        QUESTION_PROMPTS.put("timeframe",
                "请确认K线周期（TICK / 1M / 5M / 15M / 30M / 1H / 1D）：策略使用哪个周期？");
        QUESTION_PROMPTS.put("entryCondition",
                "请描述入场条件（如：快线上穿慢线时买入）。");
        QUESTION_PROMPTS.put("exitCondition",
                "请描述出场条件（如：快线下穿慢线时卖出，或触发止损）。");
        QUESTION_PROMPTS.put("riskLimits",
                "请描述风险限制（仓位、止损、最大回撤等）。");
        QUESTION_PROMPTS.put("fastWindow",
                "请给出快线窗口 fastWindow（整数 2–100，如 5）。");
        QUESTION_PROMPTS.put("slowWindow",
                "请给出慢线窗口 slowWindow（整数 3–300，且必须大于快线，如 20）。");
        QUESTION_PROMPTS.put("positionSize",
                "请给出每次入场使用的资金比例 positionSize（0–1，如 0.1 表示 10%）。");
        QUESTION_PROMPTS.put("stopLossPct",
                "请给出止损百分比 stopLossPct（0–30，如 5 表示 5%）。");
        QUESTION_PROMPTS.put("feeBps",
                "请给出单边手续费 feeBps（基点 0–100，如 2 表示 2 个基点）。");
        QUESTION_PROMPTS.put("slippageBps",
                "请给出单边滑点 slippageBps（基点 0–100，如 1 表示 1 个基点）。");
        QUESTION_PROMPTS.put("backtestAssumptions",
                "请描述回测假设（手续费、滑点、数据区间等）。");
        QUESTION_PROMPTS.put("name",
                "请为策略起一个名称（1–64 个字符）。");
    }

    static final Set<String> TIMEFRAMES = Set.of(
            "TICK", "1M", "5M", "15M", "30M", "1H", "1D");

    static final int MAX_NAME = 64;
    static final int MAX_INSTRUMENTS = 20;
    static final int MAX_INSTRUMENT_LENGTH = 16;
    static final int MAX_TEXT = 400;
    static final int MAX_ASSUMPTIONS = 500;

    private static final Pattern INSTRUMENT_TOKEN = Pattern.compile("[A-Za-z]{1,4}\\d{3,4}");
    private static final Pattern INTEGER_TOKEN = Pattern.compile("\\d+");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d+(?:\\.\\d+)?");

    // ------------------------------------------------------------------ //
    // Completeness / missing fields
    // ------------------------------------------------------------------ //

    /** Whether a required field is confirmed on the Draft. */
    static boolean fieldConfirmed(StrategySpecDraft d, String field) {
        return switch (field) {
            case "name" -> d.name() != null && !d.name().isBlank();
            case "instruments" -> d.instruments() != null && !d.instruments().isEmpty();
            case "timeframe" -> d.timeframe() != null && !d.timeframe().isBlank();
            case "fastWindow" -> d.parameters() != null && d.parameters().fastWindow() != null;
            case "slowWindow" -> d.parameters() != null && d.parameters().slowWindow() != null;
            case "positionSize" -> d.parameters() != null && d.parameters().positionSize() != null;
            case "stopLossPct" -> d.parameters() != null && d.parameters().stopLossPct() != null;
            case "feeBps" -> d.parameters() != null && d.parameters().feeBps() != null;
            case "slippageBps" -> d.parameters() != null && d.parameters().slippageBps() != null;
            case "lookbackBars" -> d.parameters() != null && d.parameters().priceBreakout() != null
                    && d.parameters().priceBreakout().lookbackBars() != null;
            case "direction" -> d.parameters() != null
                    && ((d.parameters().priceBreakout() != null
                    && d.parameters().priceBreakout().direction() != null)
                    || (d.parameters().eventSignal() != null
                    && d.parameters().eventSignal().direction() != null));
            case "entryOffsetTicks" -> d.parameters() != null
                    && d.parameters().priceBreakout() != null
                    && d.parameters().priceBreakout().entryOffsetTicks() != null;
            case "eventId", "sourceId", "contentHash", "originalPublishedAt",
                    "fetchedAt", "availableAt", "validFrom", "validUntil",
                    "relatedInstrument", "confirmationId", "confirmedByMemberId",
                    "confirmedAt", "sourceStatus" -> eventField(d, field) != null
                    && !eventField(d, field).isBlank();
            case "entryCondition" -> d.entryCondition() != null && !d.entryCondition().isBlank();
            case "exitCondition" -> d.exitCondition() != null && !d.exitCondition().isBlank();
            case "riskLimits" -> d.riskLimits() != null && !d.riskLimits().isBlank();
            case "backtestAssumptions" -> d.backtestAssumptions() != null && !d.backtestAssumptions().isBlank();
            default -> false;
        };
    }

    /** The missing required fields in question priority order. */
    public static List<String> missingFields(StrategySpecDraft d) {
        List<String> out = new ArrayList<>();
        for (String field : requiredFields(d.templateType())) {
            if (!fieldConfirmed(d, field)) out.add(field);
        }
        return out;
    }

    /** 0..100 — confirmed required fields / total required fields. */
    public static int completeness(StrategySpecDraft d) {
        List<String> required = requiredFields(d.templateType());
        int confirmed = 0;
        for (String field : required) {
            if (fieldConfirmed(d, field)) confirmed++;
        }
        return (int) Math.round(confirmed * 100.0 / required.size());
    }

    /** The ONE highest-impact question, or null when every required field is
     *  confirmed. Already-confirmed fields are never asked again. */
    public static TurnQuestion nextQuestion(StrategySpecDraft d) {
        List<String> required = requiredFields(d.templateType());
        List<String> ordered = new ArrayList<>();
        for (String field : QUESTION_ORDER) if (required.contains(field)) ordered.add(field);
        for (String field : required) if (!ordered.contains(field)) ordered.add(field);
        for (String field : ordered) {
            if (!fieldConfirmed(d, field)) {
                return new TurnQuestion(field, QUESTION_PROMPTS.getOrDefault(field,
                        "请通过结构化 parameters 提供并确认字段：" + field));
            }
        }
        return null;
    }

    public static List<String> requiredFields(String templateType) {
        List<String> common = List.of("name", "instruments", "timeframe");
        List<String> tail = List.of("positionSize", "stopLossPct", "feeBps",
                "slippageBps", "entryCondition", "exitCondition", "riskLimits",
                "backtestAssumptions");
        List<String> params = switch (templateType) {
            case StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT -> List.of(
                    "lookbackBars", "direction", "entryOffsetTicks");
            case StrategySpecDraft.TEMPLATE_EVENT_SIGNAL -> List.of(
                    "eventId", "sourceId", "contentHash", "originalPublishedAt",
                    "fetchedAt", "availableAt", "validFrom", "validUntil",
                    "relatedInstrument", "direction", "confirmationId",
                    "confirmedByMemberId", "confirmedAt", "sourceStatus");
            default -> List.of("fastWindow", "slowWindow");
        };
        List<String> out = new ArrayList<>(common);
        out.addAll(params);
        out.addAll(tail);
        return List.copyOf(out);
    }

    private static String eventField(StrategySpecDraft d, String field) {
        if (d.parameters() == null || d.parameters().eventSignal() == null) return null;
        SpecParameters.EventSignal e = d.parameters().eventSignal();
        return switch (field) {
            case "eventId" -> e.eventId(); case "sourceId" -> e.sourceId();
            case "contentHash" -> e.contentHash();
            case "originalPublishedAt" -> e.originalPublishedAt();
            case "fetchedAt" -> e.fetchedAt(); case "availableAt" -> e.availableAt();
            case "validFrom" -> e.validFrom(); case "validUntil" -> e.validUntil();
            case "relatedInstrument" -> e.relatedInstrument();
            case "confirmationId" -> e.confirmationId();
            case "confirmedByMemberId" -> e.confirmedByMemberId();
            case "confirmedAt" -> e.confirmedAt(); case "sourceStatus" -> e.sourceStatus();
            default -> null;
        };
    }

    // ------------------------------------------------------------------ //
    // Answer parsing (deterministic; throws IllegalArgumentException with a
    // user-facing message when the answer cannot be parsed into the field)
    // ------------------------------------------------------------------ //

    /**
     * Parse a user answer into a single-field DraftPatch. The returned patch
     * carries exactly ONE non-null field. Throws when the answer cannot be
     * parsed (the question is then asked again — still one question per turn).
     */
    /**
     * Extract a deterministic field correction from a free-text user message
     * (e.g. "3% 是止盈，止损 1.5%") while co-creating a Draft. This drives the
     * chat → Draft-patch path independently of which field the pending
     * question is about. Returns null when the message carries no recognized
     * correction.
     *
     * <p>Mappings (no takeProfitPct field exists, so take-profit is expressed
     * via the existing exitCondition free-text, mirroring the Draft PATCH form):
     * <ul>
     *   <li>止损 / 止损 X% → stopLossPct = X (structured)</li>
     *   <li>止盈 / 止赢 X% → exitCondition += "止盈 X%" (free text)</li>
     * </ul>
     */
    public static TaskCenterDtos.DraftPatch extractCorrection(String content) {
        if (content == null || content.isBlank()) return null;
        Double stopLoss = null;
        String takeProfitText = null;
        java.util.regex.Matcher mStop = Pattern.compile(
                "止损\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|个百分点|个点)?").matcher(content);
        if (mStop.find()) {
            try {
                double v = Double.parseDouble(mStop.group(1));
                if (v > 0 && v <= 30) stopLoss = v;
            } catch (NumberFormatException ignore) { /* skip */ }
        }
        java.util.regex.Matcher mTp = Pattern.compile(
                "(?:止盈\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|个百分点|个点)?"
                + "|(\\d+(?:\\.\\d+)?)\\s*%?\\s*(?:是|为)?\\s*止盈)").matcher(content);
        if (mTp.find()) {
            String num = mTp.group(1) != null ? mTp.group(1) : mTp.group(2);
            takeProfitText = "止盈 " + num + "%";
        }
        if (stopLoss == null && takeProfitText == null) return null;
        // Only mutate the structured stop-loss when present; take-profit goes
        // into exitCondition free-text (it is NOT a parallel Draft type).
        return new TaskCenterDtos.DraftPatch(
                null, null, null, null, null, null,
                stopLoss, null, null, null,
                takeProfitText == null ? null : takeProfitText,
                null, null, null, null);
    }

    static TaskCenterDtos.DraftPatch parseAnswer(String field, String content) {
        if (field == null) {
            throw new IllegalArgumentException("待确认字段为空（可能来自一次澄清而非字段问题）");
        }
        String text = content == null ? "" : content.trim();
        return switch (field) {
            case "name" -> {
                String name = validateName(text);
                yield new TaskCenterDtos.DraftPatch(name, null, null, null, null, null,
                        null, null, null, null, null, null, null);
            }
            case "instruments" -> {
                List<String> instruments = parseInstruments(text);
                yield new TaskCenterDtos.DraftPatch(null, instruments, null, null, null,
                        null, null, null, null, null, null, null, null);
            }
            case "timeframe" -> {
                String tf = parseTimeframe(text);
                yield new TaskCenterDtos.DraftPatch(null, null, tf, null, null, null,
                        null, null, null, null, null, null, null);
            }
            case "fastWindow" -> {
                int v = parseInteger(text, 2, 100, "fastWindow 需为 2–100 的整数");
                yield new TaskCenterDtos.DraftPatch(null, null, null, v, null, null,
                        null, null, null, null, null, null, null);
            }
            case "slowWindow" -> {
                int v = parseInteger(text, 3, 300, "slowWindow 需为 3–300 的整数");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, v, null,
                        null, null, null, null, null, null, null);
            }
            case "positionSize" -> {
                double v = parseNumber(text, 0, 1, "positionSize 需为 0–1");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, v,
                        null, null, null, null, null, null, null);
            }
            case "stopLossPct" -> {
                double v = parseNumber(text, 0, 30, "stopLossPct 需为 0–30");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, null,
                        v, null, null, null, null, null, null);
            }
            case "feeBps" -> {
                int v = parseInteger(text, 0, 100, "feeBps 需为 0–100 的整数");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, null,
                        null, v, null, null, null, null, null);
            }
            case "slippageBps" -> {
                int v = parseInteger(text, 0, 100, "slippageBps 需为 0–100 的整数");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, null,
                        null, null, v, null, null, null, null);
            }
            case "entryCondition" -> {
                String v = validateText(text, MAX_TEXT, "入场条件");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, null,
                        null, null, null, v, null, null, null);
            }
            case "exitCondition" -> {
                String v = validateText(text, MAX_TEXT, "出场条件");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, null,
                        null, null, null, null, v, null, null);
            }
            case "riskLimits" -> {
                String v = validateText(text, MAX_TEXT, "风险限制");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, null,
                        null, null, null, null, null, v, null);
            }
            case "backtestAssumptions" -> {
                String v = validateText(text, MAX_ASSUMPTIONS, "回测假设");
                yield new TaskCenterDtos.DraftPatch(null, null, null, null, null, null,
                        null, null, null, null, null, null, v);
            }
            default -> throw new IllegalArgumentException("未知字段：" + field);
        };
    }

    // ------------------------------------------------------------------ //
    // Field validators (also used by the PATCH endpoint)
    // ------------------------------------------------------------------ //

    static String validateName(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("策略名称不能为空");
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("策略名称不能为空");
        }
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("策略名称过长（最多 " + MAX_NAME + " 个字符）");
        }
        rejectControlChars(name, "策略名称包含不允许的控制字符");
        return name;
    }

    /** Canonicalize: uppercase + de-duplicate keeping first-seen order. */
    static List<String> parseInstruments(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("合约列表不能为空（如 JM2609）");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : raw.split("[,\\s，、;；]+")) {
            String s = item.trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (s.length() > MAX_INSTRUMENT_LENGTH) {
                throw new IllegalArgumentException(
                        "合约代码长度需为 1–" + MAX_INSTRUMENT_LENGTH + " 个字符");
            }
            boolean alnum = true;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) alnum = false;
            }
            if (!alnum) {
                throw new IllegalArgumentException("合约代码只能包含字母与数字（如 JM2609）");
            }
            out.add(s);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("合约列表不能为空（如 JM2609）");
        }
        if (out.size() > MAX_INSTRUMENTS) {
            throw new IllegalArgumentException("去重后合约数量需为 1–" + MAX_INSTRUMENTS + " 个");
        }
        return List.copyOf(out);
    }

    static String parseTimeframe(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("周期不能为空");
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        String normalized = switch (t) {
            case "TICK", "1M", "5M", "15M", "30M", "1H", "1D" -> t;
            case "日线", "DAILY", "D" -> "1D";
            case "5分钟", "5MIN", "M5" -> "5M";
            case "15分钟", "15MIN", "M15" -> "15M";
            case "30分钟", "30MIN", "M30" -> "30M";
            case "1小时", "60分钟", "1HOUR", "H1" -> "1H";
            case "分钟", "MIN", "1MIN" -> "1M";
            default -> null;
        };
        if (normalized == null) {
            throw new IllegalArgumentException(
                    "不支持的周期（可选：TICK、1M、5M、15M、30M、1H、1D）");
        }
        return normalized;
    }

    static int parseInteger(String text, int min, int max, String message) {
        Matcher m = INTEGER_TOKEN.matcher(text);
        if (!m.find()) throw new IllegalArgumentException(message);
        try {
            int v = Integer.parseInt(m.group());
            if (v < min || v > max) throw new IllegalArgumentException(message);
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
    }

    static double parseNumber(String text, double min, double max, String message) {
        // "5%" / "0.1" / "5% 的手续费" all parse to the first number.
        Matcher m = NUMBER_TOKEN.matcher(text);
        if (!m.find()) throw new IllegalArgumentException(message);
        try {
            double v = Double.parseDouble(m.group());
            if (Double.isNaN(v) || v < min || v > max) throw new IllegalArgumentException(message);
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
    }

    static String validateText(String raw, int maxLength, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String s = raw.trim();
        if (s.length() > maxLength) {
            throw new IllegalArgumentException(label + "过长（最多 " + maxLength + " 个字符）");
        }
        rejectControlChars(s, label + "包含不允许的控制字符");
        return s;
    }

    private static void rejectControlChars(String s, String message) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) throw new IllegalArgumentException(message);
        }
    }

    /** Extract the candidate instruments from free text (seed detection). */
    static List<String> extractInstruments(String text) {
        if (text == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = INSTRUMENT_TOKEN.matcher(text.toUpperCase(Locale.ROOT));
        while (m.find()) {
            String tok = m.group();
            if (tok.length() <= MAX_INSTRUMENT_LENGTH) out.add(tok);
        }
        return List.copyOf(out);
    }
}
