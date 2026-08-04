package com.supertrader.demo.taskcenter;

/**
 * The per-run budget policy of the Agent Runtime Harness (Module 9).
 *
 * <p>Every AgentRun carries a fixed server-side budget: max steps, max tool
 * calls and max output chars. The harness checks the budget after every step /
 * tool call / output; when a hard limit is hit it saves a RunCheckpoint and
 * the run ends (CHECKPOINTED) — it can never loop forever. Budgets are
 * server constants, never client-supplied.
 */
public final class BudgetPolicy {

    private BudgetPolicy() {}

    /** Server-side defaults for a single user turn. */
    public static final int DEFAULT_MAX_STEPS = 8;
    public static final int DEFAULT_MAX_TOOL_CALLS = 12;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 4000;
    public static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 10;

    public static RunBudget defaultBudget() {
        return new RunBudget(DEFAULT_MAX_STEPS, DEFAULT_MAX_TOOL_CALLS,
                DEFAULT_MAX_OUTPUT_CHARS);
    }

    /** Whether another step may run. */
    public static boolean stepsRemaining(RunBudget b, int stepsUsed) {
        return stepsUsed < b.maxSteps();
    }

    /** Whether another tool call may run. */
    public static boolean toolCallsRemaining(RunBudget b, int toolCallsUsed) {
        return toolCallsUsed < b.maxToolCalls();
    }

    /** Whether another output char may be appended. */
    public static boolean outputCharsRemaining(RunBudget b, int outputCharsUsed) {
        return outputCharsUsed < b.maxOutputChars();
    }
}
