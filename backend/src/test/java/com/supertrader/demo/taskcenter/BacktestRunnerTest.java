package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism + fail-closed tests of the local Backtest Runner (Module 9).
 * The computation is pure Java: it never calls an LLM / AgentScope / SimNow /
 * the native probe, and synthetic acceptance samples are never presented as
 * real market data.
 */
class BacktestRunnerTest {

    private static TaskCenterTestSupport.Stack stack;
    private static BacktestRunnerService runner;
    private static StrategySpecSnapshot snapshot;

    @BeforeAll
    static void setUp() throws Exception {
        stack = TaskCenterTestSupport.build();
        TaskCenterTestSupport.copyAcceptanceDatasets(stack);
        runner = stack.runner();
        snapshot = new StrategySpecSnapshot("snap1", "draft1", "default", "strat1",
                1, "均线策略", StrategySpecDraft.TEMPLATE_SMA_CROSS,
                List.of("JM2609"), "1D", new SpecParameters(5, 20, 0.1, 5.0, 2, 1),
                "快线上穿慢线时买入", "快线下穿慢线时卖出", "止损5%", "使用验收样本",
                "hash", "2026-08-01T00:00:00Z", "m1");
    }

    private static BacktestRunnerService.BacktestResult run(String datasetId) {
        return runner.run(snapshot, datasetId, () -> false);
    }

    @Test
    void deterministicMetricsOnAcceptanceDataset() {
        var r1 = run("acceptance-1d-jm2609");
        assertEquals(BacktestRun.STATUS_SUCCEEDED, r1.status());
        assertEquals("JM2609", r1.instrument());
        assertEquals("1D", r1.timeframe());
        assertTrue(r1.inputBars() >= BacktestRun.MIN_INPUT_BARS);
        assertTrue(r1.trades() >= 0);
        assertNotNull(r1.grossReturnPct());
        assertNotNull(r1.netReturnPct());
        assertNotNull(r1.maxDrawdownPct());
        assertNotNull(r1.winRate());
        assertEquals(Integer.valueOf(2), r1.feeAssumptionBps());
        assertEquals(Integer.valueOf(1), r1.slippageAssumptionBps());
        assertEquals(BacktestRun.SAMPLE_OUT_SAMPLE_ONLY, r1.sampleOutStatus());
        // Deterministic: same input → identical metrics.
        var r2 = run("acceptance-1d-jm2609");
        assertEquals(r1.grossReturnPct(), r2.grossReturnPct());
        assertEquals(r1.netReturnPct(), r2.netReturnPct());
        assertEquals(r1.maxDrawdownPct(), r2.maxDrawdownPct());
        assertEquals(r1.winRate(), r2.winRate());
        assertEquals(r1.trades(), r2.trades());
        var deterministic1 = (com.fasterxml.jackson.databind.node.ObjectNode)
                new ObjectMapper().valueToTree(r1);
        var deterministic2 = (com.fasterxml.jackson.databind.node.ObjectNode)
                new ObjectMapper().valueToTree(r2);
        deterministic1.remove("durationMs");
        deterministic2.remove("durationMs");
        assertEquals(deterministic1, deterministic2,
                "same input is byte-semantic identical except durationMs");
        assertTrue(r1.netReturnPct() <= r1.grossReturnPct(),
                "net return must never exceed gross (fees + slippage)");
    }

    @Test
    void unknownDatasetFailsClosed() {
        var r = run("no-such-dataset");
        assertEquals(BacktestRun.STATUS_FAILED, r.status());
        assertEquals(BacktestRunnerService.ERROR_DATA_UNAVAILABLE, r.errorCode());
    }

    @Test
    void mismatchedPeriodOrInstrumentFailsClosed() {
        var wrongInstrument = new StrategySpecSnapshot("snap2", "d1", "default",
                "s1", 1, "x", StrategySpecDraft.TEMPLATE_SMA_CROSS, List.of("IF2506"),
                "1D", new SpecParameters(5, 20, 0.1, 5.0, 2, 1), "入", "出", "风",
                "假设", "h", "2026-08-01T00:00:00Z", "m1");
        var r = runner.run(wrongInstrument, "acceptance-1d-jm2609", () -> false);
        assertEquals(BacktestRun.STATUS_FAILED, r.status());
        assertEquals(BacktestRunnerService.ERROR_DATA_MISMATCH, r.errorCode());

        var wrongTimeframe = new StrategySpecSnapshot("snap3", "d1", "default",
                "s1", 1, "x", StrategySpecDraft.TEMPLATE_SMA_CROSS, List.of("JM2609"),
                "5M", new SpecParameters(5, 20, 0.1, 5.0, 2, 1), "入", "出", "风",
                "假设", "h", "2026-08-01T00:00:00Z", "m1");
        var r2 = runner.run(wrongTimeframe, "acceptance-1d-jm2609", () -> false);
        assertEquals(BacktestRunnerService.ERROR_DATA_MISMATCH, r2.errorCode());
    }

    @Test
    void illegalParametersFailClosed() {
        var bad = new StrategySpecSnapshot("snap4", "d1", "default", "s1", 1,
                "x", StrategySpecDraft.TEMPLATE_SMA_CROSS, List.of("JM2609"), "1D",
                new SpecParameters(20, 5, 0.1, 5.0, 2, 1), "入", "出", "风", "假设",
                "h", "2026-08-01T00:00:00Z", "m1"); // slow < fast
        var r = runner.run(bad, "acceptance-1d-jm2609", () -> false);
        assertEquals(BacktestRunnerService.ERROR_ILLEGAL_PARAMETERS, r.errorCode());
    }

    @Test
    void undersizedSampleFailsClosed() throws Exception {
        // Build a tiny covering dataset (50 bars < 200).
        StringBuilder bars = new StringBuilder();
        double price = 1000.0;
        for (int i = 0; i < 50; i++) {
            String ts = java.time.Instant.parse("2026-01-01T00:00:00Z")
                    .plus(java.time.Duration.ofHours(i)).toString();
            bars.append("{\"ts\":\"").append(ts).append("\",\"open\":").append(price)
                .append(",\"high\":").append(price * 1.001)
                .append(",\"low\":").append(price * 0.999)
                .append(",\"close\":").append(price).append(",\"volume\":100},");
        }
        String json = "{\"schema\":\"backtest-dataset.v1\","
                + "\"datasetId\":\"tiny-1d-jm2609\",\"instrument\":\"JM2609\","
                + "\"timeframe\":\"1D\",\"periodStart\":\"2026-01-01T00:00:00.000Z\","
                + "\"periodEnd\":\"2026-01-03T01:00:00.000Z\","
                + "\"generatedAt\":\"2026-08-02T00:00:00Z\",\"staleAfter\":null,"
                + "\"sampleOutStatus\":\"SAMPLE_ONLY\",\"description\":\"tiny\",\"bars\":["
                + bars.substring(0, bars.length() - 1) + "]}";
        java.nio.file.Files.writeString(stack.datasetsDir().resolve("tiny.json"), json);
        stack.catalog().invalidateCacheForTests();
        var r = run("tiny-1d-jm2609");
        assertEquals(BacktestRunnerService.ERROR_SAMPLE_TOO_SMALL, r.errorCode());
        assertEquals(BacktestRun.STATUS_FAILED, r.status());
    }

    @Test
    void staleDatasetFailsClosed() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(
                stack.datasetsDir().resolve("acceptance-1d-jm2609.json")))
                .replace("\"staleAfter\": null",
                        "\"staleAfter\": \"2020-01-01T00:00:00Z\"")
                .replace("acceptance-1d-jm2609", "stale-1d-jm2609");
        java.nio.file.Files.writeString(stack.datasetsDir().resolve("stale.json"), json);
        stack.catalog().invalidateCacheForTests();
        var r = run("stale-1d-jm2609");
        assertEquals(BacktestRunnerService.ERROR_DATA_STALE, r.errorCode());
    }

    @Test
    void nonWhitelistTemplateFailsClosed() {
        var bad = new StrategySpecSnapshot("snap5", "d1", "default", "s1", 1,
                "x", "MY_CUSTOM", List.of("JM2609"), "1D",
                new SpecParameters(5, 20, 0.1, 5.0, 2, 1), "入", "出", "风", "假设",
                "h", "2026-08-01T00:00:00Z", "m1");
        var r = runner.run(bad, "acceptance-1d-jm2609", () -> false);
        assertEquals(BacktestRun.STATUS_FAILED, r.status());
        assertEquals(BacktestRunnerService.ERROR_ILLEGAL_TEMPLATE, r.errorCode());
    }

    @Test
    void cooperativeCancellationStopsMidLoop() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        var r = runner.run(snapshot, "acceptance-1d-jm2609", cancelled::get);
        assertEquals(BacktestRunnerService.ERROR_CANCELLED, r.errorCode());
        assertEquals(BacktestRun.STATUS_FAILED, r.status());
    }

    @Test
    void smaWindowComputationIsExact() {
        var bars = List.of(
                new DatasetCatalog.Bar("t1", 1, 1, 1, 1, 1),
                new DatasetCatalog.Bar("t2", 2, 2, 2, 2, 1),
                new DatasetCatalog.Bar("t3", 3, 3, 3, 3, 1),
                new DatasetCatalog.Bar("t4", 4, 4, 4, 4, 1),
                new DatasetCatalog.Bar("t5", 5, 5, 5, 5, 1));
        double[] sma3 = BacktestRunnerService.sma(bars, 3);
        assertEquals(0.0, sma3[1]); // before the window fills
        assertEquals(2.0, sma3[2]); // (1+2+3)/3
        assertEquals(3.0, sma3[3]); // (2+3+4)/3
        assertEquals(4.0, sma3[4]); // (3+4+5)/3
    }

    @Test
    void feeSlippageStopLossAndDrawdownAreDeterministic() throws Exception {
        // A fully controlled synthetic series (300 bars, ≥ MIN_INPUT_BARS):
        // bars 0..249 flat at 100; bar 250 closes at 105 → SMA(2)=102.5 crosses
        // above SMA(50)≈100.1 → ENTRY at 105; bars 251..259 stay at 105 (no
        // cross); bar 260 closes at 96 → the 5% stop (99.75) is hit and the
        // runner exits at the STOP price (the conservative branch wins over
        // the cross-down close). Params: fast=2, slow=50, positionSize=0.1,
        // stopLossPct=5, feeBps=2, slippageBps=1.
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            double close = i >= 250 && i < 260 ? 105.0 : (i == 260 ? 96.0 : 100.0);
            String ts = java.time.Instant.parse("2026-01-01T00:00:00Z")
                    .plus(java.time.Duration.ofHours(i)).toString();
            bars.append("{\"ts\":\"").append(ts).append("\",\"open\":")
                .append(close).append(",\"high\":").append(close * 1.001)
                .append(",\"low\":").append(close * 0.999)
                .append(",\"close\":").append(close).append(",\"volume\":100},");
        }
        String json = "{\"schema\":\"backtest-dataset.v1\","
                + "\"datasetId\":\"stop-series\",\"instrument\":\"JM2609\","
                + "\"timeframe\":\"1D\",\"periodStart\":\"2026-01-01T00:00:00.000Z\","
                + "\"periodEnd\":\"2026-01-13T11:00:00.000Z\","
                + "\"generatedAt\":\"2026-08-02T00:00:00Z\",\"staleAfter\":null,"
                + "\"sampleOutStatus\":\"SAMPLE_ONLY\",\"description\":\"stop-series\","
                + "\"bars\":[" + bars.substring(0, bars.length() - 1) + "]}";
        java.nio.file.Files.writeString(stack.datasetsDir().resolve("stop.json"), json);
        stack.catalog().invalidateCacheForTests();

        var snapshot = new StrategySpecSnapshot("snap6", "d1", "default", "s1", 1,
                "x", StrategySpecDraft.TEMPLATE_SMA_CROSS, List.of("JM2609"), "1D",
                new SpecParameters(2, 50, 0.1, 5.0, 2, 1), "入", "出", "风", "假设",
                "h", "2026-08-01T00:00:00Z", "m1");
        var r = runner.run(snapshot, "stop-series", () -> false);
        assertEquals(BacktestRun.STATUS_SUCCEEDED, r.status(), r.errorMessage());
        assertEquals(1, r.trades());
        assertEquals(0.0, r.winRate());
        // Signal at bar 250 close, entry at bar 251 open=105. Signal at bar 260
        // close after the stop is crossed, exit only at bar 261 open=100.
        double shares = 10000.0 / 105.0105;
        double entryFill = 105.0105;
        double exitFill = 100.0 * (1 - 0.0001);
        double expectedGross = (100.0 - 105.0) * shares / 100000.0 * 100.0;
        double expectedNet = ((exitFill - entryFill) * shares
                - 2 * (entryFill * shares) / 10000.0
                - 2 * (exitFill * shares) / 10000.0) / 100000.0 * 100.0;
        assertEquals(expectedGross, r.grossReturnPct(), 0.05);
        assertEquals(expectedNet, r.netReturnPct(), 0.05);
        // The equity loss == the net PnL → drawdown equals |netReturn|.
        assertEquals(Math.abs(expectedNet), r.maxDrawdownPct(), 0.05);
        assertEquals(Integer.valueOf(2), r.feeAssumptionBps());
        assertEquals(Integer.valueOf(1), r.slippageAssumptionBps());
        var zeroCostSnapshot = new StrategySpecSnapshot("snap6-zero", "d1", "default",
                "s1", 1, "x", StrategySpecDraft.TEMPLATE_SMA_CROSS,
                List.of("JM2609"), "1D", new SpecParameters(2, 50, 0.1, 5.0, 0, 0),
                "入", "出", "风", "假设", "h", "2026-08-01T00:00:00Z", "m1");
        var zero = runner.run(zeroCostSnapshot, "stop-series", () -> false);
        assertEquals(zero.grossReturnPct(), zero.netReturnPct());
        assertEquals(zero.grossReturnPct(), r.grossReturnPct());
        var highCostSnapshot = new StrategySpecSnapshot("snap6-high", "d1", "default",
                "s1", 1, "x", StrategySpecDraft.TEMPLATE_SMA_CROSS,
                List.of("JM2609"), "1D", new SpecParameters(2, 50, 0.1, 5.0, 100, 100),
                "入", "出", "风", "假设", "h", "2026-08-01T00:00:00Z", "m1");
        var highCost = runner.run(highCostSnapshot, "stop-series", () -> false);
        assertTrue(highCost.netReturnPct() < zero.netReturnPct());
        // Deterministic: a second run is identical.
        var r2 = runner.run(snapshot, "stop-series", () -> false);
        assertEquals(r.netReturnPct(), r2.netReturnPct());
        assertEquals(r.maxDrawdownPct(), r2.maxDrawdownPct());
        assertEquals(r.trades(), r2.trades());
    }

    @Test
    void breakoutExcludesCurrentBarAndFillsOnlyAtNextOpen() throws Exception {
        writeBreakoutDataset("breakout-next-open", 200, 123.0, 100.0);
        StrategySpecSnapshot breakout = snapshotFromParameters(
                StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT,
                "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                        + "\"lookbackBars\":20,\"direction\":\"LONG\","
                        + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                        + "\"positionSize\":0.1,\"feeBps\":0,\"slippageBps\":0}");
        var result = runner.run(breakout, "breakout-next-open", () -> false);
        assertEquals(BacktestRun.STATUS_SUCCEEDED, result.status(), result.errorMessage());
        JsonNode json = new ObjectMapper().valueToTree(result);
        assertEquals("BAR_CLOSE->NEXT_BAR_OPEN", json.path("executionTiming").asText());
        assertTrue(json.path("orders").size() >= 1);
        JsonNode entry = json.path("orders").get(0);
        assertEquals("2025-07-20T00:00:00Z", entry.path("signalBarTs").asText());
        assertEquals("2025-07-21T00:00:00Z", entry.path("fillBarTs").asText());
        assertEquals(123.0, entry.path("price").asDouble(), 0.000001);
        assertTrue(java.time.Instant.parse(entry.path("fillBarTs").asText()).isAfter(
                java.time.Instant.parse(entry.path("signalBarTs").asText())));
    }

    @Test
    void changingFutureBarsDoesNotChangeBreakoutHistoricalTrigger() throws Exception {
        writeBreakoutDataset("breakout-future-a", 200, 123.0, 100.0);
        writeBreakoutDataset("breakout-future-b", 200, 123.0, 800.0);
        StrategySpecSnapshot breakout = snapshotFromParameters(
                StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT,
                "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                        + "\"lookbackBars\":20,\"direction\":\"LONG\","
                        + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                        + "\"positionSize\":0.1,\"feeBps\":0,\"slippageBps\":0}");
        JsonNode a = new ObjectMapper().valueToTree(
                runner.run(breakout, "breakout-future-a", () -> false));
        JsonNode b = new ObjectMapper().valueToTree(
                runner.run(breakout, "breakout-future-b", () -> false));
        assertEquals(BacktestRun.STATUS_SUCCEEDED, a.path("status").asText());
        assertEquals(BacktestRun.STATUS_SUCCEEDED, b.path("status").asText());
        assertTrue(a.path("orders").size() >= 1);
        assertTrue(b.path("orders").size() >= 1);
        assertEquals(a.path("orders").get(0), b.path("orders").get(0));
    }

    @Test
    void signalOnFinalBarDoesNotInventAFill() throws Exception {
        writeBreakoutDataset("breakout-no-next", 219, 999.0, 100.0);
        StrategySpecSnapshot breakout = snapshotFromParameters(
                StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT,
                "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                        + "\"lookbackBars\":20,\"direction\":\"LONG\","
                        + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                        + "\"positionSize\":0.1,\"feeBps\":0,\"slippageBps\":0}");
        JsonNode json = new ObjectMapper().valueToTree(
                runner.run(breakout, "breakout-no-next", () -> false));
        assertEquals(BacktestRun.STATUS_SUCCEEDED, json.path("status").asText());
        assertTrue(json.path("orders").isArray());
        assertEquals(0, json.path("orders").size());
    }

    @Test
    void confirmedAvailableEventRoutesAndFillsAtNextOpen() throws Exception {
        writeBreakoutDataset("event-next-open", -1, 123.0, 100.0);
        StrategySpecSnapshot event = snapshotFromParameters(
                StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                "{\"schemaVersion\":2,\"type\":\"EVENT_SIGNAL\","
                        + "\"eventId\":\"evt-1\",\"sourceId\":\"src-1\","
                        + "\"contentHash\":\"sha256:abc\","
                        + "\"originalPublishedAt\":\"2025-07-19T00:00:00Z\","
                        + "\"fetchedAt\":\"2025-07-20T00:00:00Z\","
                        + "\"availableAt\":\"2025-07-20T00:00:00Z\","
                        + "\"validFrom\":\"2025-07-20T00:00:00Z\","
                        + "\"validUntil\":\"2025-07-25T00:00:00Z\","
                        + "\"relatedInstrument\":\"JM2609\",\"direction\":\"LONG\","
                        + "\"confirmationId\":\"confirm-1\","
                        + "\"confirmedByMemberId\":\"owner-1\","
                        + "\"confirmedAt\":\"2025-07-20T00:00:00Z\","
                        + "\"sourceStatus\":\"AVAILABLE\",\"stopLossPct\":5,"
                        + "\"positionSize\":0.1,\"feeBps\":0,\"slippageBps\":0}");
        JsonNode json = new ObjectMapper().valueToTree(
                runner.run(event, "event-next-open", () -> false));
        assertEquals(BacktestRun.STATUS_SUCCEEDED, json.path("status").asText(),
                json.path("errorMessage").asText());
        assertEquals("EVENT_AVAILABLE->NEXT_BAR_OPEN",
                json.path("executionTiming").asText());
        assertEquals("2025-07-20T00:00:00Z",
                json.path("orders").get(0).path("signalBarTs").asText());
        assertEquals("2025-07-21T00:00:00Z",
                json.path("orders").get(0).path("fillBarTs").asText());
    }

    @Test
    void smaAndEventHistoricalTriggersIgnoreChangedFutureBars() throws Exception {
        writeSmaDataset("sma-future-a", 100.0);
        writeSmaDataset("sma-future-b", 700.0);
        StrategySpecSnapshot sma = new StrategySpecSnapshot("sma", "d", "default", "s", 1,
                "x", StrategySpecDraft.TEMPLATE_SMA_CROSS, List.of("JM2609"), "1D",
                new SpecParameters(2, 20, 0.1, 5.0, 0, 0), "入", "出", "风", "SAMPLE_ONLY",
                "h", "2026-08-01T00:00:00Z", "m");
        JsonNode smaA = new ObjectMapper().valueToTree(
                runner.run(sma, "sma-future-a", () -> false));
        JsonNode smaB = new ObjectMapper().valueToTree(
                runner.run(sma, "sma-future-b", () -> false));
        assertEquals(smaA.path("orders").get(0), smaB.path("orders").get(0));

        writeBreakoutDataset("event-future-a", -1, 123.0, 100.0);
        writeBreakoutDataset("event-future-b", -1, 123.0, 700.0);
        StrategySpecSnapshot event = snapshotFromParameters(
                StrategySpecDraft.TEMPLATE_EVENT_SIGNAL, eventJson(
                        "2025-07-20T00:00:00Z", "2025-07-20T00:00:00Z",
                        "2025-07-25T00:00:00Z", "AVAILABLE", 0, 0));
        JsonNode eventA = new ObjectMapper().valueToTree(
                runner.run(event, "event-future-a", () -> false));
        JsonNode eventB = new ObjectMapper().valueToTree(
                runner.run(event, "event-future-b", () -> false));
        assertEquals(eventA.path("orders").get(0), eventB.path("orders").get(0));
    }

    @Test
    void eventExpiryFutureAvailabilityAndConfirmationTimeGateSignals() throws Exception {
        writeBreakoutDataset("event-time-gates", -1, 123.0, 100.0);
        var expired = snapshotFromParameters(StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                eventJson("2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z",
                        "2024-01-02T00:00:00Z", "AVAILABLE", 0, 0));
        var future = snapshotFromParameters(StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                eventJson("2027-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                        "2027-01-02T00:00:00Z", "AVAILABLE", 0, 0));
        assertEquals(0, new ObjectMapper().valueToTree(
                runner.run(expired, "event-time-gates", () -> false))
                .path("orders").size());
        assertEquals(0, new ObjectMapper().valueToTree(
                runner.run(future, "event-time-gates", () -> false))
                .path("orders").size());

        var confirmedLater = snapshotFromParameters(StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                eventJson("2025-07-20T00:00:00Z", "2025-07-22T00:00:00Z",
                        "2025-07-25T00:00:00Z", "AVAILABLE", 0, 0));
        JsonNode result = new ObjectMapper().valueToTree(
                runner.run(confirmedLater, "event-time-gates", () -> false));
        assertEquals("2025-07-22T00:00:00Z",
                result.path("orders").get(0).path("signalBarTs").asText());
    }

    @Test
    void breakoutAndEventCostsHaveExactDeterministicNetRelationship() throws Exception {
        writeBreakoutDataset("cost-breakout", 200, 123.0, 100.0);
        var breakoutZero = snapshotFromParameters(StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT,
                "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                        + "\"lookbackBars\":20,\"direction\":\"LONG\","
                        + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                        + "\"positionSize\":0.1,\"feeBps\":0,\"slippageBps\":0}");
        var breakoutCost = snapshotFromParameters(StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT,
                "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                        + "\"lookbackBars\":20,\"direction\":\"LONG\","
                        + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                        + "\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1}");
        var bz = runner.run(breakoutZero, "cost-breakout", () -> false);
        var bc = runner.run(breakoutCost, "cost-breakout", () -> false);
        double breakoutShares = 10_000.0 / 123.0123;
        double breakoutExpectedNet = ((99.99 - 123.0123) * breakoutShares
                - 123.0123 * breakoutShares * 2 / 10_000.0
                - 99.99 * breakoutShares * 2 / 10_000.0) / 1000.0;
        assertEquals(roundTwo(breakoutExpectedNet), bc.netReturnPct());
        assertEquals(bz.grossReturnPct(), bz.netReturnPct());
        assertTrue(bc.netReturnPct() < bc.grossReturnPct());

        writeBreakoutDataset("cost-event", -1, 123.0, 100.0);
        var eventZero = snapshotFromParameters(StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                eventJson("2025-07-20T00:00:00Z", "2025-07-20T00:00:00Z",
                        "2025-07-25T00:00:00Z", "AVAILABLE", 0, 0));
        var eventCost = snapshotFromParameters(StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                eventJson("2025-07-20T00:00:00Z", "2025-07-20T00:00:00Z",
                        "2025-07-25T00:00:00Z", "AVAILABLE", 2, 1));
        var ez = runner.run(eventZero, "cost-event", () -> false);
        var ec = runner.run(eventCost, "cost-event", () -> false);
        double eventShares = 10_000.0 / 100.01;
        double eventExpectedNet = ((99.99 - 100.01) * eventShares
                - 100.01 * eventShares * 2 / 10_000.0
                - 99.99 * eventShares * 2 / 10_000.0) / 1000.0;
        assertEquals(roundTwo(eventExpectedNet), ec.netReturnPct());
        assertEquals(ez.grossReturnPct(), ez.netReturnPct());
        assertTrue(ec.netReturnPct() < ec.grossReturnPct());
    }

    @Test
    void datasetStalenessUsesInjectedClockWithEqualityExpired() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dataset-clock-");
        java.nio.file.Files.writeString(dir.resolve("clock.json"), datasetJson(
                "clock-boundary", List.of(
                        barJson("2025-01-01T00:00:00Z", 100),
                        barJson("2025-01-02T00:00:00Z", 101)),
                "2026-08-03T00:00:00Z"));
        var ctor = assertDoesNotThrow(() -> DatasetCatalog.class.getDeclaredConstructor(
                String.class, java.time.Clock.class));
        DatasetCatalog atBoundary = ctor.newInstance(dir.toString(), java.time.Clock.fixed(
                java.time.Instant.parse("2026-08-03T00:00:00Z"), java.time.ZoneOffset.UTC));
        DatasetCatalog beforeBoundary = ctor.newInstance(dir.toString(), java.time.Clock.fixed(
                java.time.Instant.parse("2026-08-02T23:59:59.999999999Z"),
                java.time.ZoneOffset.UTC));
        assertTrue(atBoundary.coverage("JM2609", "1D").isEmpty());
        assertEquals(1, beforeBoundary.coverage("JM2609", "1D").size());
    }

    @Test
    void datasetRejectsDuplicateOutOfOrderOutOfPeriodAndUnknownFields() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dataset-order-");
        java.nio.file.Files.writeString(dir.resolve("duplicate.json"), datasetJson(
                "duplicate", List.of(barJson("2025-01-01T00:00:00Z", 100),
                        barJson("2025-01-01T00:00:00Z", 101)), null));
        java.nio.file.Files.writeString(dir.resolve("out-of-order.json"), datasetJson(
                "out-of-order", List.of(barJson("2025-01-02T00:00:00Z", 100),
                        barJson("2025-01-01T00:00:00Z", 101)), null));
        java.nio.file.Files.writeString(dir.resolve("out-of-period.json"), datasetJson(
                "out-of-period", List.of(barJson("2024-12-31T00:00:00Z", 100),
                        barJson("2025-01-01T00:00:00Z", 101)), null));
        java.nio.file.Files.writeString(dir.resolve("unknown-root.json"), datasetJson(
                "unknown-root", List.of(barJson("2025-01-01T00:00:00Z", 100),
                        barJson("2025-01-02T00:00:00Z", 101)), null)
                .replace("\"description\":\"SAMPLE_ONLY\"",
                        "\"description\":\"SAMPLE_ONLY\",\"unknown\":true"));
        java.nio.file.Files.writeString(dir.resolve("legacy-ok.json"), datasetJson(
                "legacy-ok", List.of(barJson("2025-01-01T00:00:00Z", 100),
                        barJson("2025-01-02T00:00:00Z", 101)), null));

        DatasetCatalog catalog = new DatasetCatalog(dir.toString());
        assertNull(catalog.load("duplicate"));
        assertNull(catalog.load("out-of-order"));
        assertNull(catalog.load("out-of-period"));
        assertNull(catalog.load("unknown-root"));
        assertNotNull(catalog.load("legacy-ok"), "backtest-dataset.v1 remains readable");
    }

    @Test
    void schemaV2SnapshotWithFakeCanonicalHashFailsClosed() throws Exception {
        StrategySpecSnapshot valid = snapshotFromParameters(
                StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT,
                "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                        + "\"lookbackBars\":20,\"direction\":\"LONG\","
                        + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                        + "\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1}");
        StrategySpecSnapshot tamperedHash = new StrategySpecSnapshot(valid.id(),
                valid.draftId(), valid.workspaceId(), valid.strategyId(),
                valid.versionNumber(), valid.name(), valid.templateType(),
                valid.instruments(), valid.timeframe(), valid.parameters(),
                valid.entryCondition(), valid.exitCondition(), valid.riskLimits(),
                valid.backtestAssumptions(), "fake-snapshot-hash", valid.frozenAt(),
                valid.frozenByMemberId());

        var result = runner.run(tamperedHash, "acceptance-1d-jm2609", () -> false);
        assertEquals(BacktestRun.STATUS_FAILED, result.status());
        assertEquals(BacktestRunnerService.ERROR_ILLEGAL_PARAMETERS, result.errorCode());
    }

    @Test
    void nonFinitePositionAndStopLossFailClosedAtRunnerBoundary() {
        StrategySpecSnapshot nanPosition = new StrategySpecSnapshot("nan", "d", "default",
                "s", 1, "nan", StrategySpecDraft.TEMPLATE_SMA_CROSS,
                List.of("JM2609"), "1D", new SpecParameters(5, 20, Double.NaN,
                5.0, 2, 1), "入", "出", "风", "SAMPLE_ONLY", "legacy-hash",
                "2026-08-01T00:00:00Z", "m1");
        StrategySpecSnapshot infiniteStop = new StrategySpecSnapshot("inf", "d", "default",
                "s", 1, "inf", StrategySpecDraft.TEMPLATE_SMA_CROSS,
                List.of("JM2609"), "1D", new SpecParameters(5, 20, 0.1,
                Double.POSITIVE_INFINITY, 2, 1), "入", "出", "风", "SAMPLE_ONLY",
                "legacy-hash", "2026-08-01T00:00:00Z", "m1");

        assertEquals(BacktestRunnerService.ERROR_ILLEGAL_PARAMETERS,
                runner.run(nanPosition, "acceptance-1d-jm2609", () -> false).errorCode());
        assertEquals(BacktestRunnerService.ERROR_ILLEGAL_PARAMETERS,
                runner.run(infiniteStop, "acceptance-1d-jm2609", () -> false).errorCode());
    }

    private static StrategySpecSnapshot snapshotFromParameters(String template,
                                                               String parametersJson)
            throws Exception {
        SpecParameters parameters = new ObjectMapper().readValue(parametersJson,
                SpecParameters.class);
        String hash = StrategySpecCanonicalizer.hash("x", template, List.of("JM2609"),
                "1D", parameters, "入", "出", "风", "SAMPLE_ONLY");
        return new StrategySpecSnapshot("snap-" + template, "d1", "default", "s1", 1,
                "x", template, List.of("JM2609"), "1D", parameters,
                "入", "出", "风", "SAMPLE_ONLY", hash,
                "2026-08-01T00:00:00Z", "m1");
    }

    private static String eventJson(String availableAt, String confirmedAt,
                                    String validUntil, String sourceStatus,
                                    int feeBps, int slippageBps) {
        return "{\"schemaVersion\":2,\"type\":\"EVENT_SIGNAL\","
                + "\"eventId\":\"evt-1\",\"sourceId\":\"src-1\","
                + "\"contentHash\":\"sha256:abc\","
                + "\"originalPublishedAt\":\"2024-01-01T00:00:00Z\","
                + "\"fetchedAt\":\"" + availableAt + "\","
                + "\"availableAt\":\"" + availableAt + "\","
                + "\"validFrom\":\"" + availableAt + "\","
                + "\"validUntil\":\"" + validUntil + "\","
                + "\"relatedInstrument\":\"JM2609\",\"direction\":\"LONG\","
                + "\"confirmationId\":\"c\",\"confirmedByMemberId\":\"owner\","
                + "\"confirmedAt\":\"" + confirmedAt + "\","
                + "\"sourceStatus\":\"" + sourceStatus + "\",\"stopLossPct\":5,"
                + "\"positionSize\":0.1,\"feeBps\":" + feeBps
                + ",\"slippageBps\":" + slippageBps + "}";
    }

    private static void writeSmaDataset(String datasetId, double futurePrice) throws Exception {
        StringBuilder bars = new StringBuilder();
        java.time.Instant start = java.time.Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 220; i++) {
            double close = i == 200 ? 110.0 : (i > 201 ? futurePrice : 100.0);
            double open = i == 201 ? 120.0 : close;
            String ts = start.plus(java.time.Duration.ofDays(i)).toString();
            bars.append("{\"ts\":\"").append(ts).append("\",\"open\":")
                    .append(open).append(",\"high\":").append(Math.max(open, close) + 1)
                    .append(",\"low\":").append(Math.min(open, close) - 1)
                    .append(",\"close\":").append(close).append(",\"volume\":100},");
        }
        String json = "{\"schema\":\"backtest-dataset.v1\",\"datasetId\":\""
                + datasetId + "\",\"instrument\":\"JM2609\",\"timeframe\":\"1D\","
                + "\"periodStart\":\"2025-01-01T00:00:00Z\","
                + "\"periodEnd\":\"2025-08-08T00:00:00Z\","
                + "\"generatedAt\":\"2026-08-02T00:00:00Z\",\"staleAfter\":null,"
                + "\"sampleOutStatus\":\"SAMPLE_ONLY\",\"description\":\"SAMPLE_ONLY\","
                + "\"bars\":[" + bars.substring(0, bars.length() - 1) + "]}";
        java.nio.file.Files.writeString(stack.datasetsDir().resolve(datasetId + ".json"), json);
        stack.catalog().invalidateCacheForTests();
    }

    private static double roundTwo(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void writeBreakoutDataset(String datasetId, int signalIndex,
                                             double nextOpen, double futurePrice)
            throws Exception {
        StringBuilder bars = new StringBuilder();
        java.time.Instant start = java.time.Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 220; i++) {
            double close = i == signalIndex ? 102.0 : (i > signalIndex + 1
                    && signalIndex >= 0 ? futurePrice : 100.0);
            double open = i == signalIndex + 1 ? nextOpen : close;
            double high = i == signalIndex ? 1000.0 : Math.max(open, close) + 1.0;
            double low = Math.min(open, close) - 1.0;
            String ts = start.plus(java.time.Duration.ofDays(i)).toString();
            bars.append("{\"ts\":\"").append(ts).append("\",\"open\":")
                    .append(open).append(",\"high\":").append(high)
                    .append(",\"low\":").append(low).append(",\"close\":")
                    .append(close).append(",\"volume\":100},");
        }
        String json = "{\"schema\":\"backtest-dataset.v1\","
                + "\"datasetId\":\"" + datasetId + "\",\"instrument\":\"JM2609\","
                + "\"timeframe\":\"1D\",\"periodStart\":\"2025-01-01T00:00:00Z\","
                + "\"periodEnd\":\"2025-08-08T00:00:00Z\","
                + "\"generatedAt\":\"2026-08-02T00:00:00Z\",\"staleAfter\":null,"
                + "\"sampleOutStatus\":\"SAMPLE_ONLY\",\"description\":\"SAMPLE_ONLY\","
                + "\"bars\":[" + bars.substring(0, bars.length() - 1) + "]}";
        java.nio.file.Files.writeString(stack.datasetsDir().resolve(datasetId + ".json"), json);
        stack.catalog().invalidateCacheForTests();
    }

    private static String datasetJson(String id, List<String> bars, String staleAfter) {
        return "{\"schema\":\"backtest-dataset.v1\",\"datasetId\":\"" + id
                + "\",\"instrument\":\"JM2609\",\"timeframe\":\"1D\","
                + "\"periodStart\":\"2025-01-01T00:00:00Z\","
                + "\"periodEnd\":\"2025-01-02T00:00:00Z\","
                + "\"generatedAt\":\"2026-08-02T00:00:00Z\",\"staleAfter\":"
                + (staleAfter == null ? "null" : "\"" + staleAfter + "\"")
                + ",\"sampleOutStatus\":\"SAMPLE_ONLY\","
                + "\"description\":\"SAMPLE_ONLY\",\"bars\":["
                + String.join(",", bars) + "]}";
    }

    private static String barJson(String ts, double price) {
        return "{\"ts\":\"" + ts + "\",\"open\":" + price
                + ",\"high\":" + (price + 1) + ",\"low\":" + (price - 1)
                + ",\"close\":" + price + ",\"volume\":100}";
    }
}
