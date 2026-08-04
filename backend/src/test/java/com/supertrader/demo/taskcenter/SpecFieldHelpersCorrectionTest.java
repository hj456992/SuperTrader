package com.supertrader.demo.taskcenter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SpecFieldHelpers#extractCorrection(String)} (reviewer
 * round 2 / Issue #2): deterministic extraction of a chat-driven field
 * correction into a validated DraftPatch.
 */
class SpecFieldHelpersCorrectionTest {

    @Test
    void extractsStopLossAndTakeProfitFromFreeText() {
        TaskCenterDtos.DraftPatch p = SpecFieldHelpers.extractCorrection("3% 是止盈，止损 1.5%");
        assertNotNull(p, "a recognized correction must produce a patch");
        assertEquals(1.5, p.stopLossPct(), "stopLossPct must be 1.5");
        assertEquals("止盈 3%", p.exitCondition(),
                "take-profit must be expressed via exitCondition free-text");
        assertNull(p.entryCondition());
        assertNull(p.riskLimits());
    }

    @Test
    void extractsStopLossOnly() {
        TaskCenterDtos.DraftPatch p = SpecFieldHelpers.extractCorrection("止损 2%");
        assertNotNull(p);
        assertEquals(2.0, p.stopLossPct());
        assertNull(p.exitCondition());
    }

    @Test
    void returnsNullForUnrecognizedText() {
        assertNull(SpecFieldHelpers.extractCorrection("你好，介绍均线"));
        assertNull(SpecFieldHelpers.extractCorrection(""));
        assertNull(SpecFieldHelpers.extractCorrection(null));
    }

    @Test
    void rejectsOutOfRangeStopLoss() {
        // 50% stop-loss is out of the 0–30 range → ignored → null (no take-profit either).
        assertNull(SpecFieldHelpers.extractCorrection("止损 50%"));
    }
}
