package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary tests of the deterministic StrategySpec Validator (Module 9). The
 * Validator never calls an LLM and an LLM has no power to turn a failed report
 * into a pass.
 */
class StrategySpecValidatorTest {

    private static StrategySpecValidator validator;
    private static String draftId;

    @BeforeAll
    static void setUp() throws Exception {
        var stack = TaskCenterTestSupport.build();
        TaskCenterTestSupport.copyAcceptanceDatasets(stack);
        validator = stack.validator();
        draftId = "draft-1";
    }

    private StrategySpecDraft draft(SpecParameters p, String entry, String exit,
                                    String risk, String assumptions,
                                    List<String> instruments, String timeframe,
                                    String template, String name, int completeness,
                                    List<String> missing) {
        return new StrategySpecDraft(draftId, "default", "s1", null, null, "seed1",
                name, template, instruments, timeframe, p, entry, exit, risk,
                assumptions, List.of(), completeness, missing,
                StrategySpecDraft.STATUS_CO_CREATING, "m1",
                "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");
    }

    private StrategySpecDraft completeDraft() {
        return draft(new SpecParameters(5, 20, 0.1, 5.0, 2, 1),
                "快线上穿慢线时买入", "快线下穿慢线时卖出", "止损5%", "使用验收样本",
                List.of("JM2609"), "1D", StrategySpecDraft.TEMPLATE_SMA_CROSS,
                "均线策略", 100, List.of());
    }

    @Test
    void completeSmaCrossDraftIsValidAndBacktestable() {
        var v = validator.validate(completeDraft());
        assertTrue(v.valid(), () -> "issues: " + v.issues());
        assertTrue(v.backtestable());
        assertTrue(v.issues().isEmpty());
    }

    @Test
    void nonWhitelistTemplateIsNeverBacktestable() {
        var v = validator.validate(draft(new SpecParameters(5, 20, 0.1, 5.0, 2, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D", "MY_CUSTOM",
                "x", 100, List.of()));
        assertFalse(v.valid());
        assertFalse(v.backtestable());
        assertTrue(v.issues().stream().anyMatch(i ->
                i.code().equals("TEMPLATE_NOT_WHITELISTED")));
    }

    @Test
    void incompleteDraftFailsWithFieldLevelIssues() {
        var v = validator.validate(draft(new SpecParameters(null, null, null, null,
                null, null), null, null, null, null, null, null,
                StrategySpecDraft.TEMPLATE_SMA_CROSS, null, 30,
                List.of("name", "instruments", "timeframe", "fastWindow",
                        "slowWindow", "positionSize", "stopLossPct", "feeBps",
                        "slippageBps", "entryCondition", "exitCondition",
                        "riskLimits", "backtestAssumptions")));
        assertFalse(v.valid());
        assertFalse(v.backtestable());
        assertTrue(v.issues().stream().anyMatch(i -> i.code().equals("FIELD_MISSING")));
        assertTrue(v.issues().stream().anyMatch(i -> i.field().equals("instruments")));
        assertTrue(v.issues().stream().anyMatch(i -> i.field().equals("fastWindow")));
    }

    @Test
    void parameterBoundsAreEnforced() {
        assertFalse(validator.validate(draft(new SpecParameters(1, 20, 0.1, 5.0, 2, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of())).valid());
        assertFalse(validator.validate(draft(new SpecParameters(20, 20, 0.1, 5.0, 2, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of())).valid());
        assertFalse(validator.validate(draft(new SpecParameters(5, 301, 0.1, 5.0, 2, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of())).valid());
        assertFalse(validator.validate(draft(new SpecParameters(5, 20, 1.5, 5.0, 2, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of())).valid());
        assertFalse(validator.validate(draft(new SpecParameters(5, 20, 0.1, 31.0, 2, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of())).valid());
        assertFalse(validator.validate(draft(new SpecParameters(5, 20, 0.1, 5.0, 101, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of())).valid());
        assertFalse(validator.validate(draft(new SpecParameters(5, 20, 0.1, 5.0, 2, -1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of())).valid());
        // slowWindow == fastWindow is a conflict.
        assertTrue(validator.validate(draft(new SpecParameters(20, 20, 0.1, 5.0, 2, 1),
                "买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of()))
                .issues().stream().anyMatch(i -> i.code().equals("SLOW_NOT_ABOVE_FAST")));
    }

    @Test
    void entryExitConflictIsRejected() {
        var v = validator.validate(draft(new SpecParameters(5, 20, 0.1, 5.0, 2, 1),
                "快线上穿时买入", "快线上穿时买入", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of()));
        assertFalse(v.valid());
        assertTrue(v.issues().stream().anyMatch(i ->
                i.code().equals("ENTRY_EXIT_CONFLICT")));
    }

    @Test
    void instrumentsAndTimeframeWhitelist() {
        var badInstrument = validator.validate(draft(new SpecParameters(5, 20, 0.1,
                5.0, 2, 1), "买入", "卖出", "风控", "假设", List.of("jm-2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of()));
        assertFalse(badInstrument.valid());
        var badTimeframe = validator.validate(draft(new SpecParameters(5, 20, 0.1,
                5.0, 2, 1), "买入", "卖出", "风控", "假设", List.of("JM2609"), "4H",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of()));
        assertFalse(badTimeframe.valid());
    }

    @Test
    void datasetCoverageIsFailClosed() {
        // JM2609 5M is covered by acceptance-5m; JM2609 TICK is not.
        var uncovered = validator.validate(draft(new SpecParameters(5, 20, 0.1, 5.0,
                2, 1), "买入", "卖出", "风控", "假设", List.of("JM2609"), "TICK",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of()));
        assertFalse(uncovered.backtestable());
        assertTrue(uncovered.issues().stream().anyMatch(i ->
                i.code().equals("DATA_UNAVAILABLE")));
        var covered5m = validator.validate(draft(new SpecParameters(5, 20, 0.1, 5.0,
                2, 1), "买入", "卖出", "风控", "假设", List.of("JM2609"), "5M",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of()));
        assertTrue(covered5m.backtestable());
    }

    @Test
    void bannedContentIsRejected() {
        var v = validator.validate(draft(new SpecParameters(5, 20, 0.1, 5.0, 2, 1),
                "如果密码是123456就买入", "卖出", "风控", "假设", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_SMA_CROSS, "x", 100, List.of()));
        assertFalse(v.valid());
        assertTrue(v.issues().stream().anyMatch(i ->
                i.code().equals("FORBIDDEN_CONTENT")));
        // Normal research language is NOT a false positive.
        assertFalse(StrategySpecValidator.containsBannedContent(
                "快线上穿慢线时买入，止损5%"));
        assertFalse(StrategySpecValidator.containsBannedContent("系统化趋势跟踪"));
        assertTrue(StrategySpecValidator.containsBannedContent("curl http://x"));
        assertTrue(StrategySpecValidator.containsBannedContent("exec(\"rm\")"));
        assertTrue(StrategySpecValidator.containsBannedContent("password=abc"));
    }

    @Test
    void validatorVersionIsFixed() {
        assertEquals("1.0.0", validator.version());
    }

    @Test
    void allThreeDiscriminatedTemplatesValidateAndLegacySmaJsonStillReads() throws Exception {
        var mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        String common = "\"id\":\"d2\",\"workspaceId\":\"default\","
                + "\"sessionId\":\"s1\",\"name\":\"x\","
                + "\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\","
                + "\"entryCondition\":\"入场\",\"exitCondition\":\"出场\","
                + "\"riskLimits\":\"风控\",\"backtestAssumptions\":\"SAMPLE_ONLY\","
                + "\"evidence\":[],\"completeness\":100,\"missingFields\":[],"
                + "\"status\":\"CO_CREATING\",\"createdByMemberId\":\"m1\","
                + "\"createdAt\":\"2026-08-01T00:00:00Z\","
                + "\"updatedAt\":\"2026-08-01T00:00:00Z\"";

        var legacy = mapper.readValue("{" + common
                + ",\"templateType\":\"SMA_CROSS\",\"parameters\":{"
                + "\"fastWindow\":5,\"slowWindow\":20,\"positionSize\":0.1,"
                + "\"stopLossPct\":5,\"feeBps\":2,\"slippageBps\":1}}",
                StrategySpecDraft.class);
        assertTrue(validator.validate(legacy).backtestable());
        assertEquals(Integer.valueOf(5), legacy.parameters().fastWindow());

        var breakout = mapper.readValue("{" + common
                + ",\"templateType\":\"PRICE_BREAKOUT\",\"parameters\":{"
                + "\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                + "\"lookbackBars\":20,\"direction\":\"LONG\","
                + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                + "\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1}}",
                StrategySpecDraft.class);
        assertTrue(validator.validate(breakout).backtestable(),
                () -> validator.validate(breakout).issues().toString());

        var event = mapper.readValue("{" + common
                + ",\"templateType\":\"EVENT_SIGNAL\",\"parameters\":{"
                + "\"schemaVersion\":2,\"type\":\"EVENT_SIGNAL\","
                + "\"eventId\":\"evt-1\",\"sourceId\":\"src-1\","
                + "\"contentHash\":\"sha256:abc\","
                + "\"originalPublishedAt\":\"2025-01-02T00:00:00Z\","
                + "\"fetchedAt\":\"2025-01-02T00:01:00Z\","
                + "\"availableAt\":\"2025-01-02T00:02:00Z\","
                + "\"validFrom\":\"2025-01-02T00:02:00Z\","
                + "\"validUntil\":\"2025-01-10T00:00:00Z\","
                + "\"relatedInstrument\":\"JM2609\",\"direction\":\"LONG\","
                + "\"confirmationId\":\"confirm-1\","
                + "\"confirmedByMemberId\":\"m-owner\","
                + "\"confirmedAt\":\"2025-01-02T00:03:00Z\","
                + "\"sourceStatus\":\"AVAILABLE\",\"stopLossPct\":5,"
                + "\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1}}",
                StrategySpecDraft.class);
        assertTrue(validator.validate(event).backtestable(),
                () -> validator.validate(event).issues().toString());
    }

    @Test
    void discriminatedParametersFailClosedOnMismatchUnknownOrInvalidEventFacts() throws Exception {
        var mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        String breakout = "{\"schemaVersion\":2,\"type\":\"PRICE_BREAKOUT\","
                + "\"lookbackBars\":20,\"direction\":\"LONG\","
                + "\"entryOffsetTicks\":0,\"stopLossPct\":5,"
                + "\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1}";
        SpecParameters p = mapper.readValue(breakout, SpecParameters.class);
        var mismatch = draft(p, "入", "出", "风", "SAMPLE_ONLY",
                List.of("JM2609"), "1D", StrategySpecDraft.TEMPLATE_SMA_CROSS,
                "x", 100, List.of());
        var mismatchResult = validator.validate(mismatch);
        assertFalse(mismatchResult.valid());
        assertTrue(mismatchResult.issues().stream().anyMatch(i ->
                i.code().equals("PARAMETER_TYPE_MISMATCH")));

        assertThrows(Exception.class, () -> mapper.readValue(
                breakout.substring(0, breakout.length() - 1) + ",\"unknown\":1}",
                SpecParameters.class));

        String badEvent = "{\"schemaVersion\":2,\"type\":\"EVENT_SIGNAL\","
                + "\"eventId\":\"e\",\"sourceId\":\"s\",\"contentHash\":\"h\","
                + "\"originalPublishedAt\":\"2025-01-02T00:00:00Z\","
                + "\"fetchedAt\":\"2025-01-02T00:01:00Z\","
                + "\"availableAt\":\"2025-01-02T00:02:00Z\","
                + "\"validFrom\":\"2025-01-02T00:02:00Z\","
                + "\"validUntil\":\"2025-01-10T00:00:00Z\","
                + "\"relatedInstrument\":\"JM2609\",\"direction\":\"LONG\","
                + "\"confirmationId\":\"c\",\"confirmedByMemberId\":\"m\","
                + "\"confirmedAt\":\"2025-01-02T00:01:59Z\","
                + "\"sourceStatus\":\"AVAILABLE\",\"stopLossPct\":5,"
                + "\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1}";
        var bad = draft(mapper.readValue(badEvent, SpecParameters.class), "入", "出", "风",
                "SAMPLE_ONLY", List.of("JM2609"), "1D", "EVENT_SIGNAL", "x", 100,
                List.of());
        assertFalse(validator.validate(bad).valid());
        assertTrue(validator.validate(bad).issues().stream().anyMatch(i ->
                i.code().equals("EVENT_CONFIRMATION_BEFORE_AVAILABLE")));

        String missingLookback = breakout.replace("\"lookbackBars\":20,", "");
        var missing = draft(mapper.readValue(missingLookback, SpecParameters.class),
                "入", "出", "风", "SAMPLE_ONLY", List.of("JM2609"), "1D",
                StrategySpecDraft.TEMPLATE_PRICE_BREAKOUT, "x", 100, List.of());
        assertFalse(validator.validate(missing).valid());
        assertTrue(validator.validate(missing).issues().stream().anyMatch(i ->
                i.field().equals("lookbackBars") && i.code().equals("PARAMETER_MISSING")));
    }

    @Test
    void eventSourceConfirmationAndIsoTimesAreIndependentFailClosedGates() {
        SpecParameters.EventSignal base = new SpecParameters.EventSignal(
                "e", "s", "h", "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z",
                "2025-01-02T00:00:00Z", "2025-01-02T00:00:00Z",
                "2025-10-01T00:00:00Z", "JM2609", "LONG", "c", "owner",
                "2025-01-02T00:00:00Z", "AVAILABLE", 5.0, 0.1, 2, 1);
        var unavailable = eventDraft(new SpecParameters.EventSignal(base.eventId(),
                base.sourceId(), base.contentHash(), base.originalPublishedAt(), base.fetchedAt(),
                base.availableAt(), base.validFrom(), base.validUntil(), base.relatedInstrument(),
                base.direction(), base.confirmationId(), base.confirmedByMemberId(),
                base.confirmedAt(), "REVOKED", base.stopLossPct(), base.positionSize(),
                base.feeBps(), base.slippageBps()));
        assertTrue(validator.validate(unavailable).issues().stream().anyMatch(i ->
                i.code().equals("EVENT_SOURCE_UNAVAILABLE")));

        var unconfirmed = eventDraft(new SpecParameters.EventSignal(base.eventId(),
                base.sourceId(), base.contentHash(), base.originalPublishedAt(), base.fetchedAt(),
                base.availableAt(), base.validFrom(), base.validUntil(), base.relatedInstrument(),
                base.direction(), null, null, null, base.sourceStatus(), base.stopLossPct(),
                base.positionSize(), base.feeBps(), base.slippageBps()));
        assertFalse(validator.validate(unconfirmed).valid());
        assertTrue(validator.validate(unconfirmed).issues().stream().anyMatch(i ->
                i.field().equals("confirmationId") && i.code().equals("PARAMETER_MISSING")));

        var badIso = eventDraft(new SpecParameters.EventSignal(base.eventId(),
                base.sourceId(), base.contentHash(), "2025-01-01 00:00:00",
                base.fetchedAt(), base.availableAt(), base.validFrom(), base.validUntil(),
                base.relatedInstrument(), base.direction(), base.confirmationId(),
                base.confirmedByMemberId(), base.confirmedAt(), base.sourceStatus(),
                base.stopLossPct(), base.positionSize(), base.feeBps(), base.slippageBps()));
        assertTrue(validator.validate(badIso).issues().stream().anyMatch(i ->
                i.code().equals("EVENT_TIME_INVALID")));
    }

    private StrategySpecDraft eventDraft(SpecParameters.EventSignal event) {
        return draft(SpecParameters.event(event), "入", "出", "风", "SAMPLE_ONLY",
                List.of("JM2609"), "1D", StrategySpecDraft.TEMPLATE_EVENT_SIGNAL,
                "x", 100, List.of());
    }
}
