package com.supertrader.demo.taskcenter;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The Agent Runtime Harness capability registry (Module 9 / Module 10).
 *
 * <p>The ALLOWED capabilities are EXACTLY:
 * <ul>
 *   <li>strategy.read — read strategy / draft metadata;</li>
 *   <li>strategy.draft.update — update a co-created Draft field;</li>
 *   <li>strategy.validate — run the deterministic Validator;</li>
 *   <li>rag.search.local — search the local knowledge base;</li>
 *   <li>backtest.plan — plan (never execute) a backtest;</li>
 *   <li>backtest.result.summarize — explain an EXISTING structured result;</li>
 *   <li>simnow.read-only.snapshot — Module 10: the read-only SimNow risk
 *       snapshot capability. It is REGISTERED here (documented in the
 *       allowlist) but deliberately has NO ToolProxy schema handler — the
 *       Agent Runtime Harness can never invoke it (a proxy call is refused
 *       fail-closed before anything runs); the ONLY entry point is the
 *       explicit, user-clicked risk-centre endpoint.</li>
 * </ul>
 *
 * <p>Deliberately NOT registered (fail-closed: an unregistered capability is
 * refused before anything runs): order / cancel-order / modify-order / trade /
 * execution-live / account-bind / auto-recovery / paper-runner / live-runner /
 * future-gateway. The registry is a hard allowlist — a tool that is not here
 * can never be invoked through the ToolProxy.
 */
@Component
public class CapabilityRegistry {

    public static final String STRATEGY_READ = "strategy.read";
    public static final String STRATEGY_DRAFT_UPDATE = "strategy.draft.update";
    public static final String STRATEGY_VALIDATE = "strategy.validate";
    public static final String RAG_SEARCH_LOCAL = "rag.search.local";
    public static final String BACKTEST_PLAN = "backtest.plan";
    public static final String BACKTEST_RESULT_SUMMARIZE = "backtest.result.summarize";
    public static final String SIMNOW_READ_ONLY_SNAPSHOT = "simnow.read-only.snapshot";

    /** The exhaustive documented-capability set (public so tests can assert it
     *  contains no trading capability and that Paper/Live/Future Gateway stay
     *  out). NOTE: this set DOCUMENTS every capability known to the platform,
     *  including the human-only SimNow read-only snapshot — it is NOT the set
     *  of capabilities the Agent may invoke. Use {@link #AGENT_CALLABLE} for
     *  that. */
    public static final Set<String> ALLOWED = Set.of(
            STRATEGY_READ, STRATEGY_DRAFT_UPDATE, STRATEGY_VALIDATE,
            RAG_SEARCH_LOCAL, BACKTEST_PLAN, BACKTEST_RESULT_SUMMARIZE,
            SIMNOW_READ_ONLY_SNAPSHOT);

    /** The capabilities the Agent Runtime Harness (ToolProxy / ToolGuard) may
     *  EVER invoke (defect E). The SimNow read-only snapshot is a
     *  Runner / human-endpoint capability ONLY — it is documented in
     *  {@link #ALLOWED} for auditability but is NEVER Agent-callable: the
     *  only entry point is the explicit risk-centre endpoint. */
    public static final Set<String> AGENT_CALLABLE = Set.of(
            STRATEGY_READ, STRATEGY_DRAFT_UPDATE, STRATEGY_VALIDATE,
            RAG_SEARCH_LOCAL, BACKTEST_PLAN, BACKTEST_RESULT_SUMMARIZE);

    /** Capabilities that mutate a Draft (require OWNER / ADMIN / TRADER).
     *  {@code simnow.read-only.snapshot} is deliberately NOT here: the Agent
     *  path can never invoke it; the risk-centre endpoint enforces its own
     *  RBAC (OWNER / ADMIN / TRADER, never VIEWER). */
    public static final Set<String> WRITE_CAPABILITIES = Set.of(
            STRATEGY_DRAFT_UPDATE, STRATEGY_VALIDATE, BACKTEST_PLAN);

    /** Whether a capability is DOCUMENTED in the platform (allowlist for
     *  audit). The SimNow read-only snapshot IS documented here. */
    public boolean isRegistered(String capability) {
        return capability != null && ALLOWED.contains(capability);
    }

    /** Whether a capability may be invoked by the AGENT Runtime Harness
     *  (ToolProxy / ToolGuard). The SimNow read-only snapshot is NEVER
     *  Agent-callable (defect E). */
    public boolean isAgentCallable(String capability) {
        return capability != null && AGENT_CALLABLE.contains(capability);
    }
}
