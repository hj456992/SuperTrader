package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * The stable, structured intent of one user turn (Task 2 / design §6).
 *
 * <p>Four layers:
 * <pre>
 *   Speech Act  +  Domain Object  +  Desired Mutation  +  Authorization State
 * </pre>
 * The structured intent is what every downstream decision is based on. It is
 * NEVER a command: {@code Intent ≠ Command}, and a high model confidence can
 * NEVER authorize a high-impact action — those always go through an explicit
 * confirmation / approval / risk-gate path.
 *
 * <p>The deterministic rules take priority over the model output. The model may
 * suggest labels, candidate fields, ambiguities and evidence references, but it
 * can never set {@code authorization=AUTH_CONFIRMED}, never mutate an
 * execution, never register a capability and never expand the budget. The
 * reconciler clamps those before publishing the result.
 *
 * <p>Allowed fields: primaryIntent / labels / speechAct / domain / targetType /
 * targetId / mutation / authorization / modelConfidence / calibratedConfidence
 * / extractedFields / ambiguities / evidenceRefs / requiresConfirmation /
 * nextQuestion / modelUnavailable. There is deliberately NO hidden
 * chain-of-thought, raw prompt or credential field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentResult(
        @JsonProperty("primaryIntent") String primaryIntent,
        @JsonProperty("labels") List<String> labels,
        @JsonProperty("speechAct") String speechAct,
        @JsonProperty("domain") String domain,
        @JsonProperty("targetType") String targetType,
        @JsonProperty("targetId") String targetId,
        @JsonProperty("mutation") String mutation,
        @JsonProperty("authorization") String authorization,
        @JsonProperty("modelConfidence") double modelConfidence,
        @JsonProperty("calibratedConfidence") double calibratedConfidence,
        @JsonProperty("extractedFields") Map<String, Object> extractedFields,
        @JsonProperty("ambiguities") List<String> ambiguities,
        @JsonProperty("evidenceRefs") List<String> evidenceRefs,
        @JsonProperty("requiresConfirmation") boolean requiresConfirmation,
        @JsonProperty("nextQuestion") String nextQuestion,
        @JsonProperty("modelUnavailable") boolean modelUnavailable) {

    // ----- Intent labels (multi-label vocabulary; design §6.2) ----- //
    public static final String GENERAL_QA = "GENERAL_QA";
    public static final String MARKET_QUERY = "MARKET_QUERY";
    public static final String RESEARCH = "RESEARCH";
    public static final String STRATEGY_HYPOTHESIS = "STRATEGY_HYPOTHESIS";
    public static final String STRATEGY_CANDIDATE = "STRATEGY_CANDIDATE";
    public static final String STRATEGY_REFINEMENT = "STRATEGY_REFINEMENT";
    public static final String BACKTEST_REQUEST = "BACKTEST_REQUEST";
    public static final String EXECUTION_APPLICATION = "EXECUTION_APPLICATION";
    public static final String APPROVAL_DECISION = "APPROVAL_DECISION";
    public static final String RISK_OPERATION = "RISK_OPERATION";
    public static final String NAVIGATION_REQUEST = "NAVIGATION_REQUEST";

    /** The exhaustive set of labels a model / rule may attach. Unknown labels
     *  from a model are dropped by the reconciler. */
    public static final List<String> ALLOWED_LABELS = List.of(
            GENERAL_QA, MARKET_QUERY, RESEARCH, STRATEGY_HYPOTHESIS,
            STRATEGY_CANDIDATE, STRATEGY_REFINEMENT, BACKTEST_REQUEST,
            EXECUTION_APPLICATION, APPROVAL_DECISION, RISK_OPERATION,
            NAVIGATION_REQUEST);

    // ----- Speech acts ----- //
    public static final String SPEECH_REQUEST = "REQUEST";
    public static final String SPEECH_INFORM = "INFORM";
    public static final String SPEECH_DECIDE = "DECIDE";

    // ----- Domains ----- //
    public static final String DOMAIN_STRATEGY = "STRATEGY";
    public static final String DOMAIN_MARKET = "MARKET";
    public static final String DOMAIN_RESEARCH = "RESEARCH";
    public static final String DOMAIN_EXECUTION = "EXECUTION";
    public static final String DOMAIN_SYSTEM = "SYSTEM";
    public static final String DOMAIN_GENERAL = "GENERAL";

    // ----- Target types ----- //
    public static final String TARGET_STRATEGY_SEED = "STRATEGY_SEED";
    public static final String TARGET_STRATEGY_DRAFT = "STRATEGY_DRAFT";

    // ----- Mutations ----- //
    public static final String MUTATION_NONE = "NONE";
    public static final String MUTATION_CREATE_SEED = "CREATE_SEED";
    public static final String MUTATION_UPDATE_FIELD = "UPDATE_FIELD";
    public static final String MUTATION_CLARIFY = "CLARIFY";
    public static final String MUTATION_EXECUTE = "EXECUTE";
    /** Refused mutations (capability register / budget expand / rule bypass) —
     *  normalized to {@link #MUTATION_NONE} with requiresConfirmation. */
    public static final List<String> REFUSED_MUTATIONS = List.of(
            "REGISTER_CAPABILITY", "EXPAND_BUDGET", "EXECUTE",
            "ORDER_SUBMIT", "ORDER_CANCEL", "LIVE_TRADE", "BYPASS_RULES");

    // ----- Authorization states ----- //
    public static final String AUTH_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String AUTH_NOT_CONFIRMED = "NOT_CONFIRMED";
    public static final String AUTH_CONFIRMED = "CONFIRMED";

    @JsonCreator
    public IntentResult {}
}
