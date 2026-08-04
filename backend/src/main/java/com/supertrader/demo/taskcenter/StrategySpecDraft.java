package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A declarative, code-free StrategySpec Draft (Module 9).
 *
 * <p>The Draft is the co-creation layer's product: it is editable, carries the
 * confirmed fields + field-level {@link FieldEvidence}, a deterministic
 * completeness score and the missing-fields list. A Draft is NEVER executable:
 * it contains no source code, no script, no prompt, no expression evaluation,
 * no dynamic class name, no shell command, no URL, no tool name, no MCP
 * permission, no account/credential and no trading instruction.
 *
 * <p>Status machine (server-enforced): CO_CREATING → DRAFT_READY /
 * VALIDATION_FAILED (after the deterministic Validator) → PENDING_APPROVAL
 * (explicit user submission) → APPROVED → FROZEN (OWNER/ADMIN approval +
 * immutable freeze into a {@link StrategySpecSnapshot} + a Module-8 immutable
 * StrategyVersion), or PENDING_APPROVAL → REJECTED. A frozen Draft can never be
 * modified in place; changing a frozen strategy requires a NEW Draft.
 *
 * <p>Allowed fields (strict whitelist): id / workspaceId / sessionId /
 * strategyId (nullable) / baseVersionNumber (nullable) / seedId (nullable) /
 * name / templateType / instruments / timeframe / parameters / entryCondition /
 * exitCondition / riskLimits / backtestAssumptions / evidence / completeness /
 * missingFields / status / createdByMemberId / createdAt / updatedAt.
 */
public record StrategySpecDraft(
        @JsonProperty("id") String id,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("strategyId") String strategyId,
        @JsonProperty("baseVersionNumber") Integer baseVersionNumber,
        @JsonProperty("seedId") String seedId,
        @JsonProperty("name") String name,
        @JsonProperty("templateType") String templateType,
        @JsonProperty("instruments") List<String> instruments,
        @JsonProperty("timeframe") String timeframe,
        @JsonProperty("parameters") SpecParameters parameters,
        @JsonProperty("entryCondition") String entryCondition,
        @JsonProperty("exitCondition") String exitCondition,
        @JsonProperty("riskLimits") String riskLimits,
        @JsonProperty("backtestAssumptions") String backtestAssumptions,
        @JsonProperty("evidence") List<FieldEvidence> evidence,
        @JsonProperty("completeness") int completeness,
        @JsonProperty("missingFields") List<String> missingFields,
        @JsonProperty("status") String status,
        @JsonProperty("createdByMemberId") String createdByMemberId,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt) {

    public static final String STATUS_CO_CREATING = "CO_CREATING";
    public static final String STATUS_DRAFT_READY = "DRAFT_READY";
    public static final String STATUS_VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FROZEN = "FROZEN";

    public static final String TEMPLATE_SMA_CROSS = "SMA_CROSS";
    public static final String TEMPLATE_PRICE_BREAKOUT = "PRICE_BREAKOUT";
    public static final String TEMPLATE_EVENT_SIGNAL = "EVENT_SIGNAL";
    public static final java.util.Set<String> TEMPLATE_WHITELIST = java.util.Set.of(
            TEMPLATE_SMA_CROSS, TEMPLATE_PRICE_BREAKOUT, TEMPLATE_EVENT_SIGNAL);

    /** The ordered set of required fields (drives completeness + questioning). */
    public static final List<String> REQUIRED_FIELDS = List.of(
            "name", "instruments", "timeframe",
            "fastWindow", "slowWindow", "positionSize", "stopLossPct",
            "feeBps", "slippageBps",
            "entryCondition", "exitCondition", "riskLimits", "backtestAssumptions");

    @JsonCreator
    public StrategySpecDraft {}
}
