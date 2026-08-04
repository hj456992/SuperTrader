package com.supertrader.demo.taskcenter;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Defence-in-depth middleware of the task-center agent (Module 9): denies any
 * tool call whose name is not in the six-capability allowlist — the same
 * allowlist as {@link CapabilityRegistry}. Runs regardless of what is
 * registered in the Toolkit, so even a mistakenly-registered trading tool
 * could never execute. Denied calls log the tool NAME only (never arguments,
 * which could be tainted).
 */
public final class TaskCenterToolGuard implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TaskCenterToolGuard.class);

    /** The exhaustive Agent-callable allowlist (defect E): the six Module-9
     *  capabilities ONLY. The SimNow read-only snapshot is a human-endpoint
     *  capability and is deliberately NOT here, so the Agent can never invoke
     *  it even if a handler were mistakenly registered. Public so tests can
     *  assert it contains no trading tool and no SimNow capability. */
    public static final Set<String> ALLOWED = CapabilityRegistry.AGENT_CALLABLE;

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input.toolCalls();
        if (calls != null) {
            for (ToolUseBlock call : calls) {
                String name = call.getName();
                // The model sends tool names with underscores (OpenAI's
                // function-name regex only allows [a-zA-Z0-9_-]); our internal
                // capability allowlist uses dot-namespaced identifiers. Accept
                // both forms by normalizing underscores → dots.
                String normalized = name == null ? null : name.replace('_', '.');
                if (name == null || (!ALLOWED.contains(name) && !ALLOWED.contains(normalized))) {
                    log.warn("TaskCenterToolGuard DENIED tool call: name={} "
                            + "(not in the capability allowlist)", name);
                    return Flux.empty();
                }
            }
        }
        return next.apply(input);
    }
}
