package com.supertrader.demo.taskcenter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The deterministic anti-infinite-loop guard of the unique Agent Runtime
 * Harness (Task 4 / design §7.5).
 *
 * <p>It tracks four loop conditions:
 * <ol>
 *   <li><b>budget exhaustion</b> — steps / tool calls / output chars against the
 *       fixed server budget;</li>
 *   <li><b>repeated identical actions</b> — {@code capability + canonical
 *       arguments hash + context version}; a repeat is a {@link #CACHED_REPEAT}
 *       (reuse the prior result) or, when it would block progress, a
 *       {@link #LOOP_DETECTED};</li>
 *   <li><b>no progress</b> — draft version + completeness + evidence set +
 *       validation issue set + pending question unchanged across three
 *       consecutive steps → {@link #NO_PROGRESS};</li>
 *   <li><b>oscillation</b> — the last four actions form A-B-A-B →
 *       {@link #LOOP_DETECTED}.</li>
 * </ol>
 *
 * <p>The guard is pure and not thread-safe; the harness owns one instance per
 * run and checks it before/after each step. A {@link #cancel()}ed guard stops
 * accepting new steps (the controller honors {@link #isCancelled()}).
 */
public final class LoopGuard {

    public static final String LOOP_DETECTED = "LOOP_DETECTED";
    public static final String CACHED_REPEAT = "CACHED_REPEAT";
    public static final String NO_PROGRESS = "NO_PROGRESS";

    private static final int NO_PROGRESS_WINDOW = 3;
    private static final int OSCILLATION_WINDOW = 4;

    private final RunBudget budget;
    private final Deque<ActionFingerprint> recentActions = new ArrayDeque<>();
    private final List<ProgressFingerprint> recentProgress = new ArrayList<>();
    private volatile boolean cancelled;

    public LoopGuard(RunBudget budget) {
        this.budget = budget;
    }

    /** Mark the run cancelled; no new steps should start afterwards. */
    public void cancel() {
        this.cancelled = true;
    }

    /** Whether the run has been cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns a checkpoint reason if the budget is exhausted, else null.
     *
     * @param stepsUsed the number of steps already taken
     */
    public String checkBudget(int stepsUsed) {
        if (stepsUsed >= budget.maxSteps()) {
            return RunCheckpoint.REASON_MAX_STEPS;
        }
        return null;
    }

    /**
     * Observe one action fingerprint. Returns a loop signal if the action is a
     * repeat / oscillation, else null (allowed). A successful repeat can be
     * served from cache ({@link #CACHED_REPEAT}); a blocking repeat or an
     * A-B-A-B pattern is {@link #LOOP_DETECTED}.
     */
    public String observe(ActionFingerprint action) {
        recentActions.addLast(action);
        // Trim to the oscillation window.
        while (recentActions.size() > OSCILLATION_WINDOW) {
            recentActions.removeFirst();
        }
        // Oscillation: A-B-A-B in the last four.
        if (isOscillation()) {
            return LOOP_DETECTED;
        }
        // Repeated identical action: if the immediately previous action equals
        // this one, it's a repeat.
        if (recentActions.size() >= 2) {
            ActionFingerprint[] arr = recentActions.toArray(new ActionFingerprint[0]);
            if (Objects.equals(arr[arr.length - 1], arr[arr.length - 2])) {
                return CACHED_REPEAT;
            }
        }
        return null;
    }

    /**
     * Observe one progress fingerprint. Returns {@link #NO_PROGRESS} if the
     * progress has been unchanged across {@value #NO_PROGRESS_WINDOW}
     * consecutive steps, else null.
     */
    public String observeProgress(ProgressFingerprint progress) {
        recentProgress.add(progress);
        if (recentProgress.size() > NO_PROGRESS_WINDOW) {
            recentProgress.remove(0);
        }
        if (recentProgress.size() == NO_PROGRESS_WINDOW) {
            ProgressFingerprint first = recentProgress.get(0);
            boolean allSame = true;
            for (ProgressFingerprint fp : recentProgress) {
                if (!Objects.equals(fp, first)) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                return NO_PROGRESS;
            }
        }
        return null;
    }

    private boolean isOscillation() {
        if (recentActions.size() < OSCILLATION_WINDOW) return false;
        ActionFingerprint[] arr = recentActions.toArray(new ActionFingerprint[0]);
        int n = arr.length;
        ActionFingerprint a = arr[n - 4];
        ActionFingerprint b = arr[n - 3];
        ActionFingerprint c = arr[n - 2];
        ActionFingerprint d = arr[n - 1];
        // A-B-A-B pattern.
        return Objects.equals(a, c) && Objects.equals(b, d) && !Objects.equals(a, b);
    }

    /** An action fingerprint: capability + canonical arguments + context version. */
    public record ActionFingerprint(String capability, String canonicalArguments,
                                    int contextVersion) {}

    /** A progress fingerprint: draft version, completeness, evidence set,
     *  validation issue set, pending question. */
    public record ProgressFingerprint(int draftVersion, int completeness,
                                      Set<String> evidenceIds,
                                      List<String> validationIssues,
                                      String pendingQuestion) {}
}
