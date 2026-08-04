package com.supertrader.demo.taskcenter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4 tests for the {@link LoopGuard} — the deterministic anti-infinite-loop
 * component of the unique Agent Runtime Harness (design §7.5).
 *
 * <p>It must detect: budget exhaustion, repeated identical actions (cache or
 * {@code LOOP_DETECTED}), no-progress over consecutive steps, and A-B-A-B
 * oscillation. A cancelled run stops accepting new steps.
 */
class LoopGuardTest {

    @Test
    void budgetExhaustionAfterMaxSteps() {
        LoopGuard g = new LoopGuard(BudgetPolicy.defaultBudget());
        for (int i = 0; i < 8; i++) {
            assertNull(g.checkBudget(i), "step " + i + " should be within budget");
        }
        // The 9th step (index 8) exceeds maxSteps=8 → BUDGET_EXHAUSTED.
        String reason = g.checkBudget(8);
        assertEquals(RunCheckpoint.REASON_MAX_STEPS, reason);
    }

    @Test
    void repeatedIdenticalActionIsCachedOrLoopDetected() {
        LoopGuard g = new LoopGuard(BudgetPolicy.defaultBudget());
        LoopGuard.ActionFingerprint a = new LoopGuard.ActionFingerprint(
                CapabilityRegistry.RAG_SEARCH_LOCAL, "query=gold", 1);
        // First time: allowed, no loop.
        assertNull(g.observe(a));
        // Second identical action immediately after: a repeat → must signal.
        String signal = g.observe(a);
        assertNotNull(signal);
        assertTrue(LoopGuard.LOOP_DETECTED.equals(signal)
                || LoopGuard.CACHED_REPEAT.equals(signal),
                "expected LOOP_DETECTED or CACHED_REPEAT, got " + signal);
    }

    @Test
    void oscillationABABIsDetected() {
        LoopGuard g = new LoopGuard(BudgetPolicy.defaultBudget());
        LoopGuard.ActionFingerprint a = new LoopGuard.ActionFingerprint(
                CapabilityRegistry.RAG_SEARCH_LOCAL, "q=1", 1);
        LoopGuard.ActionFingerprint b = new LoopGuard.ActionFingerprint(
                CapabilityRegistry.RAG_SEARCH_LOCAL, "q=2", 1);
        assertNull(g.observe(a));
        assertNull(g.observe(b));
        assertNull(g.observe(a));
        String signal = g.observe(b); // A-B-A-B
        assertEquals(LoopGuard.LOOP_DETECTED, signal);
    }

    @Test
    void distinctActionsDoNotTriggerLoop() {
        LoopGuard g = new LoopGuard(BudgetPolicy.defaultBudget());
        LoopGuard.ActionFingerprint a = new LoopGuard.ActionFingerprint(
                CapabilityRegistry.RAG_SEARCH_LOCAL, "q=1", 1);
        LoopGuard.ActionFingerprint b = new LoopGuard.ActionFingerprint(
                CapabilityRegistry.RAG_SEARCH_LOCAL, "q=2", 1);
        LoopGuard.ActionFingerprint c = new LoopGuard.ActionFingerprint(
                CapabilityRegistry.STRATEGY_VALIDATE, "draftId=d1", 1);
        assertNull(g.observe(a));
        assertNull(g.observe(b));
        assertNull(g.observe(c));
        // No A-B-A-B; no identical repeat.
    }

    @Test
    void noProgressOverThreeStepsIsDetected() {
        LoopGuard g = new LoopGuard(BudgetPolicy.defaultBudget());
        LoopGuard.ProgressFingerprint fp = new LoopGuard.ProgressFingerprint(
                1, 50, java.util.Set.of("ev1"), java.util.List.of("issue1"), null);
        assertNull(g.observeProgress(fp));
        assertNull(g.observeProgress(fp));
        String signal = g.observeProgress(fp); // 3rd unchanged
        assertEquals(LoopGuard.NO_PROGRESS, signal);
    }

    @Test
    void progressResetsNoProgressCounter() {
        LoopGuard g = new LoopGuard(BudgetPolicy.defaultBudget());
        LoopGuard.ProgressFingerprint fp1 = new LoopGuard.ProgressFingerprint(
                1, 50, java.util.Set.of("ev1"), java.util.List.of(), null);
        LoopGuard.ProgressFingerprint fp2 = new LoopGuard.ProgressFingerprint(
                2, 60, java.util.Set.of("ev1", "ev2"), java.util.List.of(), null);
        assertNull(g.observeProgress(fp1));
        assertNull(g.observeProgress(fp1));
        // Progress changes → counter resets.
        assertNull(g.observeProgress(fp2));
        assertNull(g.observeProgress(fp2));
        // Only 2 unchanged now, not 3.
    }

    @Test
    void cancelledRunStopsAcceptingNewSteps() {
        LoopGuard g = new LoopGuard(BudgetPolicy.defaultBudget());
        assertFalse(g.isCancelled());
        g.cancel();
        assertTrue(g.isCancelled());
        // After cancel, observe still records but the controller checks
        // isCancelled() before starting a new step.
    }
}
