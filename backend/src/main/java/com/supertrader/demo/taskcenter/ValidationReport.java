package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The field-level report of the deterministic StrategySpec Validator (Module
 * 9). The Validator is pure, deterministic Java: it NEVER calls an LLM, and an
 * LLM / Agent has no power to turn a failed report into a pass.
 *
 * <p>{@code valid} — no blocking issue; {@code backtestable} — valid AND the
 * dataset covers the required instrument / timeframe / period; {@code issues}
 * — field-level blocking problems; {@code warnings} — non-blocking notes;
 * {@code validatorVersion} — the fixed rule-set version.
 */
public record ValidationReport(
        @JsonProperty("id") String id,
        @JsonProperty("draftId") String draftId,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("valid") boolean valid,
        @JsonProperty("backtestable") boolean backtestable,
        @JsonProperty("issues") List<ValidationIssue> issues,
        @JsonProperty("warnings") List<String> warnings,
        @JsonProperty("validatedAt") String validatedAt,
        @JsonProperty("validatorVersion") String validatorVersion) {

    @JsonCreator
    public ValidationReport {}
}
