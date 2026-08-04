package com.supertrader.demo.agentscope;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * The ONLY tools ever registered into the AgentScope toolkit.
 *
 * Every method is explicitly read-only: it explains or echoes already-sanitised
 * diagnostic text. There is deliberately NO method that places, cancels, or
 * recovers any order. The {@link ReadOnlyToolGuard} middleware independently
 * enforces the allowlist at call time.
 */
public class DiagnosticTools {

    @Tool(name = "explain_stage",
          description = "Given a stage name and its PASS/FAIL/SKIPPED status plus a sanitised reason, return a short factual explanation of why.",
          readOnly = true)
    public String explainStage(
            @ToolParam(name = "stage", description = "stage id") String stage,
            @ToolParam(name = "status", description = "PASS, FAIL or SKIPPED") String status,
            @ToolParam(name = "reason", description = "sanitised machine-readable reason") String reason) {
        // Pure, side-effect-free explanation. No network, no SDK, no mutation.
        return switch (nullSafe(status).toUpperCase()) {
            case "PASS"    -> "Stage " + stage + " succeeded. Reason: " + nullSafe(reason) + ".";
            case "FAIL"    -> "Stage " + stage + " failed. Reason: " + nullSafe(reason)
                              + ". This is a runnable stage that did not complete; treat as a real failure.";
            case "SKIPPED" -> "Stage " + stage + " was skipped. Reason: " + nullSafe(reason)
                              + ". It could not run for a documented reason and is NOT a success.";
            default        -> "Stage " + stage + " has unknown status " + status + ".";
        };
    }

    @Tool(name = "summarize_overall",
          description = "Given an overall PASS/FAIL/SKIPPED, return the fail-closed interpretation.",
          readOnly = true)
    public String summarizeOverall(
            @ToolParam(name = "overall", description = "overall result") String overall) {
        return switch (nullSafe(overall).toUpperCase()) {
            case "PASS"    -> "Overall PASS: at least one stage passed and no runnable stage failed.";
            case "FAIL"    -> "Overall FAIL (fail-closed): at least one runnable stage failed.";
            case "SKIPPED" -> "Overall SKIPPED: no stage passed and none failed (all skipped/not-run).";
            default        -> "Overall result unknown.";
        };
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
