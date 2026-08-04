package com.supertrader.demo.taskcenter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Deterministic, local, SAMPLE_ONLY runner with an explicit three-template route. */
@Component
public class BacktestRunnerService implements BacktestExecutor {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunnerService.class);

    static final String ERROR_DATA_UNAVAILABLE = "DATA_UNAVAILABLE";
    static final String ERROR_DATA_STALE = "DATA_STALE";
    static final String ERROR_DATA_MISMATCH = "DATA_MISMATCH";
    static final String ERROR_ILLEGAL_TEMPLATE = "ILLEGAL_TEMPLATE";
    static final String ERROR_ILLEGAL_PARAMETERS = "ILLEGAL_PARAMETERS";
    static final String ERROR_SAMPLE_TOO_SMALL = "SAMPLE_TOO_SMALL";
    static final String ERROR_CANCELLED = "CANCELLED";
    static final String ERROR_INTERNAL = "INTERNAL";
    static final String ERROR_INVALID_RESULT = "INVALID_RESULT";

    private final DatasetCatalog catalog;

    public BacktestRunnerService(DatasetCatalog catalog) {
        this.catalog = catalog;
    }

    /** One immutable order fill. A fill timestamp is always after its signal timestamp. */
    public record OrderFill(String action, String direction, String signalAt, String fillAt,
                            String signalBarTs, String fillBarTs, double rawPrice,
                            double price, double fee, double slippage, String reason) {}

    public record BacktestResult(
            String status, String datasetId, String instrument, String timeframe,
            String periodStart, String periodEnd, int inputBars, int trades,
            Double grossReturnPct, Double netReturnPct, Double maxDrawdownPct,
            Double winRate, Integer feeAssumptionBps, Integer slippageAssumptionBps,
            String sampleOutStatus, long durationMs, String errorCode,
            String errorMessage, String templateType, String snapshotContentHash,
            String datasetContentHash, String executionTiming, List<OrderFill> orders) {

        /** Source-compatible constructor for existing controlled test executors. */
        public BacktestResult(String status, String datasetId, String instrument,
                              String timeframe, String periodStart, String periodEnd,
                              int inputBars, int trades, Double grossReturnPct,
                              Double netReturnPct, Double maxDrawdownPct, Double winRate,
                              Integer feeAssumptionBps, Integer slippageAssumptionBps,
                              String sampleOutStatus, long durationMs, String errorCode,
                              String errorMessage) {
            this(status, datasetId, instrument, timeframe, periodStart, periodEnd,
                    inputBars, trades, grossReturnPct, netReturnPct, maxDrawdownPct,
                    winRate, feeAssumptionBps, slippageAssumptionBps, sampleOutStatus,
                    durationMs, errorCode, errorMessage, null, null, null, null, List.of());
        }
    }

    public BacktestResult run(StrategySpecSnapshot snapshot, String datasetId,
                              BooleanSupplier cancelled) {
        long started = System.nanoTime();
        if (snapshot == null) {
            return failed(datasetId, null, null, null, null,
                    ERROR_INTERNAL, "规格快照为空", started);
        }
        if (!StrategySpecDraft.TEMPLATE_WHITELIST.contains(snapshot.templateType())) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_ILLEGAL_TEMPLATE,
                    "非白名单模板不可回测：" + nullSafe(snapshot.templateType()), started);
        }
        if (snapshot.parameters() != null
                && snapshot.parameters().schemaVersion() >= SpecParameters.CURRENT_SCHEMA_VERSION) {
            String canonicalHash;
            try {
                canonicalHash = StrategySpecCanonicalizer.hash(snapshot.name(),
                        snapshot.templateType(), snapshot.instruments(), snapshot.timeframe(),
                        snapshot.parameters(), snapshot.entryCondition(), snapshot.exitCondition(),
                        snapshot.riskLimits(), snapshot.backtestAssumptions());
            } catch (RuntimeException e) {
                canonicalHash = null;
            }
            if (!java.util.Objects.equals(canonicalHash, snapshot.contentHash())) {
                return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                        snapshot.templateType(), snapshot.contentHash(),
                        ERROR_ILLEGAL_PARAMETERS,
                        "schema-v2 规格快照 canonical hash 不匹配", started);
            }
        }
        if (snapshot.instruments() == null || snapshot.instruments().size() != 1) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_DATA_MISMATCH,
                    "当前垂直切片只支持单标的", started);
        }
        DatasetCatalog.Dataset ds = catalog.load(datasetId);
        if (ds == null) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_DATA_UNAVAILABLE,
                    "本地数据集不存在：" + datasetId, started);
        }
        if (!ds.instrument().equalsIgnoreCase(snapshot.instruments().get(0))
                || !ds.timeframe().equalsIgnoreCase(snapshot.timeframe())) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_DATA_MISMATCH,
                    "数据集合约或周期与策略不一致", started);
        }
        if (catalog.isStale(ds)) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_DATA_STALE,
                    "数据集已过期（staleAfter），fail-closed 拒绝回测", started);
        }
        if (ds.bars().size() < BacktestRun.MIN_INPUT_BARS) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_SAMPLE_TOO_SMALL,
                    "样本不足（" + ds.bars().size() + " 根 < "
                            + BacktestRun.MIN_INPUT_BARS + " 根）", started);
        }
        String paramIssue = parametersValid(snapshot.templateType(), snapshot.parameters(),
                snapshot.instruments().get(0));
        if (paramIssue != null) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_ILLEGAL_PARAMETERS,
                    paramIssue, started);
        }
        try {
            BacktestResult result = compute(snapshot, ds, cancelled, started);
            log.info("Backtest completed template={} status={} dataset={} trades={} net={}",
                    snapshot.templateType(), result.status(), datasetId, result.trades(),
                    result.netReturnPct());
            return result;
        } catch (CancelledException e) {
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_CANCELLED,
                    "回测已取消（本地任务取消，与交易撤单无关）", started);
        } catch (Throwable t) {
            log.warn("Backtest runner failed: {}", t.getClass().getSimpleName());
            return failed(datasetId, snapshot.instruments(), snapshot.timeframe(),
                    snapshot.templateType(), snapshot.contentHash(), ERROR_INTERNAL,
                    "回测执行失败：" + t.getClass().getSimpleName(), started);
        }
    }

    private static final class CancelledException extends RuntimeException {}
    private record Pending(String action, int direction, int signalIndex,
                           String signalAt, String reason) {}

    private BacktestResult compute(StrategySpecSnapshot snapshot,
                                   DatasetCatalog.Dataset ds,
                                   BooleanSupplier cancelled, long startedNanos) {
        SpecParameters p = snapshot.parameters();
        List<DatasetCatalog.Bar> bars = ds.bars();
        double[] fast = p.sma() == null ? null : sma(bars, p.fastWindow());
        double[] slow = p.sma() == null ? null : sma(bars, p.slowWindow());
        List<OrderFill> orders = new ArrayList<>();

        double equity = 100_000.0;
        double peak = equity;
        double maxDrawdown = 0;
        double grossPnl = 0;
        double netPnl = 0;
        int roundTrips = 0;
        int wins = 0;
        boolean inPosition = false;
        int direction = 0;
        double entryRaw = 0;
        double entryFill = 0;
        double entryFee = 0;
        double shares = 0;
        Pending pending = null;
        boolean eventConsumed = false;

        for (int i = 0; i < bars.size(); i++) {
            if (i % 10 == 0 && cancelled.getAsBoolean()) throw new CancelledException();
            DatasetCatalog.Bar bar = bars.get(i);

            // A signal produced at i-1 close may only fill at this i open.
            if (pending != null) {
                double raw = bar.open();
                double slipAmount = raw * p.slippageBps() / 10_000.0;
                if ("ENTRY".equals(pending.action())) {
                    direction = pending.direction();
                    entryRaw = raw;
                    entryFill = raw + direction * slipAmount;
                    shares = equity * p.positionSize() / entryFill;
                    entryFee = feeOf(entryFill, shares, p.feeBps());
                    inPosition = true;
                    orders.add(new OrderFill("ENTRY", direction > 0 ? "LONG" : "SHORT",
                            pending.signalAt(), SpecParameters.FILL_NEXT_BAR_OPEN,
                            bars.get(pending.signalIndex()).ts(), bar.ts(), raw, entryFill,
                            round8(entryFee), round8(Math.abs(entryFill - raw) * shares),
                            pending.reason()));
                } else if (inPosition) {
                    double exitFill = raw - direction * slipAmount;
                    double exitFee = feeOf(exitFill, shares, p.feeBps());
                    double grossTrade = (raw - entryRaw) * shares * direction;
                    double netTrade = (exitFill - entryFill) * shares * direction
                            - entryFee - exitFee;
                    grossPnl += grossTrade;
                    netPnl += netTrade;
                    equity += netTrade;
                    roundTrips++;
                    if (netTrade > 0) wins++;
                    peak = Math.max(peak, equity);
                    maxDrawdown = Math.max(maxDrawdown,
                            peak <= 0 ? 0 : (peak - equity) / peak * 100.0);
                    orders.add(new OrderFill("EXIT", direction > 0 ? "LONG" : "SHORT",
                            pending.signalAt(), SpecParameters.FILL_NEXT_BAR_OPEN,
                            bars.get(pending.signalIndex()).ts(), bar.ts(), raw, exitFill,
                            round8(exitFee), round8(Math.abs(exitFill - raw) * shares),
                            pending.reason()));
                    inPosition = false;
                    direction = 0;
                }
                pending = null;
            }

            // A last-bar signal has no i+1 bar and therefore is not queued.
            if (i + 1 >= bars.size()) continue;
            if (!inPosition) {
                Pending entry = entrySignal(snapshot.templateType(), p, bars, fast, slow,
                        i, eventConsumed);
                if (entry != null) {
                    pending = entry;
                    if (StrategySpecDraft.TEMPLATE_EVENT_SIGNAL.equals(snapshot.templateType())) {
                        eventConsumed = true;
                    }
                }
            } else {
                pending = exitSignal(snapshot.templateType(), p, bars, fast, slow,
                        i, direction, entryRaw);
            }
        }

        double grossReturn = grossPnl / 100_000.0 * 100.0;
        double netReturn = netPnl / 100_000.0 * 100.0;
        double winRate = roundTrips == 0 ? 0 : wins * 100.0 / roundTrips;
        String timing = p.signalAt() + "->" + p.fillAt();
        return new BacktestResult(BacktestRun.STATUS_SUCCEEDED, ds.datasetId(),
                ds.instrument(), ds.timeframe(), ds.periodStart(), ds.periodEnd(),
                bars.size(), roundTrips, round2(grossReturn), round2(netReturn),
                round2(maxDrawdown), round2(winRate), p.feeBps(), p.slippageBps(),
                ds.sampleOutStatus(), elapsed(startedNanos), null, null,
                snapshot.templateType(), snapshot.contentHash(), ds.contentHash(), timing,
                List.copyOf(orders));
    }

    private static Pending entrySignal(String template, SpecParameters p,
                                       List<DatasetCatalog.Bar> bars,
                                       double[] fast, double[] slow, int i,
                                       boolean eventConsumed) {
        return switch (template) {
            case StrategySpecDraft.TEMPLATE_SMA_CROSS -> {
                int w = p.slowWindow();
                boolean cross = i >= w - 1 && fast[i] > slow[i]
                        && (i == w - 1 || fast[i - 1] <= slow[i - 1]);
                yield cross ? new Pending("ENTRY", 1, i,
                        SpecParameters.SIGNAL_BAR_CLOSE, "SMA_CROSS_UP") : null;
            }
            case StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT -> {
                SpecParameters.PriceBreakout b = p.priceBreakout();
                if (i < b.lookbackBars()) yield null;
                double priorHigh = Double.NEGATIVE_INFINITY;
                double priorLow = Double.POSITIVE_INFINITY;
                for (int j = i - b.lookbackBars(); j < i; j++) {
                    priorHigh = Math.max(priorHigh, bars.get(j).high());
                    priorLow = Math.min(priorLow, bars.get(j).low());
                }
                boolean longSide = "LONG".equals(b.direction());
                boolean hit = longSide
                        ? bars.get(i).close() > priorHigh + b.entryOffsetTicks()
                        : bars.get(i).close() < priorLow - b.entryOffsetTicks();
                yield hit ? new Pending("ENTRY", longSide ? 1 : -1, i,
                        SpecParameters.SIGNAL_BAR_CLOSE, "PRICE_BREAKOUT") : null;
            }
            case StrategySpecDraft.TEMPLATE_EVENT_SIGNAL -> {
                if (eventConsumed) yield null;
                SpecParameters.EventSignal e = p.eventSignal();
                Instant barTs = Instant.parse(bars.get(i).ts());
                Instant start = max(Instant.parse(e.availableAt()),
                        Instant.parse(e.confirmedAt()), Instant.parse(e.validFrom()));
                Instant end = Instant.parse(e.validUntil());
                boolean active = !barTs.isBefore(start) && !barTs.isAfter(end);
                yield active ? new Pending("ENTRY", "LONG".equals(e.direction()) ? 1 : -1,
                        i, SpecParameters.SIGNAL_EVENT_AVAILABLE, "EVENT_SIGNAL") : null;
            }
            default -> null;
        };
    }

    private static Pending exitSignal(String template, SpecParameters p,
                                      List<DatasetCatalog.Bar> bars,
                                      double[] fast, double[] slow, int i,
                                      int direction, double entryRaw) {
        double close = bars.get(i).close();
        boolean stopped = p.stopLossPct() > 0 && (direction > 0
                ? close <= entryRaw * (1 - p.stopLossPct() / 100.0)
                : close >= entryRaw * (1 + p.stopLossPct() / 100.0));
        if (stopped) {
            return new Pending("EXIT", direction, i, p.signalAt(), "STOP_LOSS");
        }
        return switch (template) {
            case StrategySpecDraft.TEMPLATE_SMA_CROSS -> {
                boolean cross = i > 0 && fast[i] < slow[i] && fast[i - 1] >= slow[i - 1];
                yield cross ? new Pending("EXIT", direction, i,
                        SpecParameters.SIGNAL_BAR_CLOSE, "SMA_CROSS_DOWN") : null;
            }
            case StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT -> {
                SpecParameters.PriceBreakout b = p.priceBreakout();
                if (i < b.lookbackBars()) yield null;
                double priorHigh = Double.NEGATIVE_INFINITY;
                double priorLow = Double.POSITIVE_INFINITY;
                for (int j = i - b.lookbackBars(); j < i; j++) {
                    priorHigh = Math.max(priorHigh, bars.get(j).high());
                    priorLow = Math.min(priorLow, bars.get(j).low());
                }
                boolean opposite = direction > 0 ? close < priorLow : close > priorHigh;
                yield opposite ? new Pending("EXIT", direction, i,
                        SpecParameters.SIGNAL_BAR_CLOSE, "OPPOSITE_BREAKOUT") : null;
            }
            case StrategySpecDraft.TEMPLATE_EVENT_SIGNAL -> {
                boolean expired = !Instant.parse(bars.get(i).ts())
                        .isBefore(Instant.parse(p.eventSignal().validUntil()));
                yield expired ? new Pending("EXIT", direction, i,
                        SpecParameters.SIGNAL_EVENT_AVAILABLE, "EVENT_VALIDITY_ENDED") : null;
            }
            default -> null;
        };
    }

    static double[] sma(List<DatasetCatalog.Bar> bars, int window) {
        double[] out = new double[bars.size()];
        double sum = 0;
        for (int i = 0; i < bars.size(); i++) {
            sum += bars.get(i).close();
            if (i >= window) sum -= bars.get(i - window).close();
            if (i >= window - 1) out[i] = sum / window;
        }
        return out;
    }

    static String parametersValid(SpecParameters p) {
        return parametersValid(StrategySpecDraft.TEMPLATE_SMA_CROSS, p, null);
    }

    static String parametersValid(String template, SpecParameters p, String instrument) {
        if (p == null) return "参数缺失";
        if (!template.equals(p.type())) return "参数 type 与模板不匹配";
        if (p.positionSize() == null || !Double.isFinite(p.positionSize())
                || p.positionSize() < 0 || p.positionSize() > 1)
            return "positionSize 需为 0–1";
        if (p.stopLossPct() == null || !Double.isFinite(p.stopLossPct())
                || p.stopLossPct() < 0 || p.stopLossPct() > 30)
            return "stopLossPct 需为 0–30";
        if (p.feeBps() == null || p.feeBps() < 0 || p.feeBps() > 100)
            return "feeBps 需为 0–100";
        if (p.slippageBps() == null || p.slippageBps() < 0 || p.slippageBps() > 100)
            return "slippageBps 需为 0–100";
        return switch (template) {
            case StrategySpecDraft.TEMPLATE_SMA_CROSS -> {
                if (p.sma() == null || p.fastWindow() == null || p.fastWindow() < 2
                        || p.fastWindow() > 100) yield "fastWindow 需为 2–100 的整数";
                if (p.slowWindow() == null || p.slowWindow() < 3 || p.slowWindow() > 300)
                    yield "slowWindow 需为 3–300 的整数";
                if (p.slowWindow() <= p.fastWindow()) yield "slowWindow 必须大于 fastWindow";
                yield null;
            }
            case StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT -> {
                SpecParameters.PriceBreakout b = p.priceBreakout();
                if (p.positionSize() <= 0) yield "positionSize 需大于 0 且不超过 1";
                if (b == null || b.lookbackBars() == null || b.lookbackBars() < 2
                        || b.lookbackBars() > 300) yield "lookbackBars 需为 2–300";
                if (!List.of("LONG", "SHORT").contains(b.direction())) yield "direction 非法";
                if (b.entryOffsetTicks() == null || b.entryOffsetTicks() < 0
                        || b.entryOffsetTicks() > 100) yield "entryOffsetTicks 需为 0–100";
                yield timingValid(p, SpecParameters.SIGNAL_BAR_CLOSE);
            }
            case StrategySpecDraft.TEMPLATE_EVENT_SIGNAL -> p.positionSize() <= 0
                    ? "positionSize 需大于 0 且不超过 1" : eventIssue(p, instrument);
            default -> "未知模板";
        };
    }

    private static String eventIssue(SpecParameters p, String instrument) {
        SpecParameters.EventSignal e = p.eventSignal();
        if (e == null) return "事件参数缺失";
        if (!"AVAILABLE".equals(e.sourceStatus())) return "事件来源必须 AVAILABLE";
        if (!List.of("LONG", "SHORT").contains(e.direction())) return "direction 非法";
        if (instrument != null && !instrument.equals(e.relatedInstrument()))
            return "事件关联标的与策略不一致";
        for (String v : new String[]{e.eventId(), e.sourceId(), e.contentHash(),
                e.originalPublishedAt(), e.fetchedAt(), e.availableAt(), e.validFrom(),
                e.validUntil(), e.relatedInstrument(), e.confirmationId(),
                e.confirmedByMemberId(), e.confirmedAt()}) {
            if (v == null || v.isBlank()) return "事件事实缺失";
        }
        try {
            Instant published = Instant.parse(e.originalPublishedAt());
            Instant fetched = Instant.parse(e.fetchedAt());
            Instant available = Instant.parse(e.availableAt());
            Instant from = Instant.parse(e.validFrom());
            Instant until = Instant.parse(e.validUntil());
            Instant confirmed = Instant.parse(e.confirmedAt());
            if (fetched.isBefore(published)) return "fetchedAt 早于 originalPublishedAt";
            if (confirmed.isBefore(available)) return "confirmedAt 早于 availableAt";
            if (until.isBefore(from)) return "事件有效期非法";
        } catch (Exception ex) {
            return "事件时间必须为 ISO-8601";
        }
        return timingValid(p, SpecParameters.SIGNAL_EVENT_AVAILABLE);
    }

    private static String timingValid(SpecParameters p, String signalAt) {
        return signalAt.equals(p.signalAt())
                && SpecParameters.FILL_NEXT_BAR_OPEN.equals(p.fillAt())
                ? null : "执行时序非法";
    }

    private BacktestResult failed(String datasetId, List<String> instruments,
                                  String timeframe, String templateType,
                                  String snapshotHash, String code, String message,
                                  long startedNanos) {
        String instrument = instruments == null || instruments.isEmpty()
                ? null : instruments.get(0);
        return new BacktestResult(BacktestRun.STATUS_FAILED, datasetId, instrument,
                timeframe, null, null, 0, 0, null, null, null, null, null, null,
                null, elapsed(startedNanos), code, message, templateType, snapshotHash,
                null, null, List.of());
    }

    private static double feeOf(double fill, double shares, int feeBps) {
        return fill * shares * feeBps / 10_000.0;
    }
    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
    private static String nullSafe(String s) { return s == null ? "" : s; }
    private static double round2(double x) { return Math.round(x * 100.0) / 100.0; }
    private static double round8(double x) { return Math.round(x * 100_000_000.0) / 100_000_000.0; }
    private static Instant max(Instant a, Instant b, Instant c) {
        Instant max = a.isAfter(b) ? a : b;
        return max.isAfter(c) ? max : c;
    }
}
