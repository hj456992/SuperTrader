package com.supertrader.demo.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Defence-in-depth middleware: independently denies any tool call whose name is
 * not in the read-only allowlist. This runs regardless of what is registered in
 * the Toolkit, so even a mistakenly-registered trading tool could never execute.
 *
 * If a denied call is attempted, the agent receives an error result and the
 * incident is logged (tool name only — never arguments, which could be tainted).
 */
public final class ReadOnlyToolGuard implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyToolGuard.class);

    /**
     * The exhaustive allowlist of permitted tool names. Public so tests can
     * assert it contains no trading tool.
     */
    public static final Set<String> ALLOWED = Set.of("explain_stage", "summarize_overall");

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input.toolCalls();
        if (calls != null) {
            for (ToolUseBlock call : calls) {
                String name = call.getName();
                if (name == null || !ALLOWED.contains(name)) {
                    log.warn("ReadOnlyToolGuard DENIED tool call: name={} (not in read-only allowlist)",
                            name);
                    // Refuse to proceed with this acting step by returning an
                    // empty flux; the agent's reasoning loop will observe the
                    // missing result. This guarantees no unauthorised tool runs.
                    return Flux.empty();
                }
            }
        }
        return next.apply(input);
    }
}
