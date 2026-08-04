package com.supertrader.demo.taskcenter;

import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the real AgentScope task-center agent against the verified 2.0.0 API
 * (Module 9). The model is an OpenAI-compatible {@link OpenAIChatModel} pointed
 * at the configured base URL; the API key is read from the named environment
 * variable ONLY and is never stored in a field, never logged, never
 * serialised.
 *
 * <p>The agent is wired with a read-only co-creation system prompt and a
 * Toolkit of the SIX capability tools only, plus the
 * {@link TaskCenterToolGuard} middleware enforcing the same allowlist at call
 * time. There is deliberately NO order / cancel / trade tool anywhere.
 *
 * <p>The agent is used ONLY inside the Agent Runtime Harness, and only when a
 * model key is configured; without a key the harness uses its deterministic
 * planner and clearly marks MODEL_UNAVAILABLE (model answers are never faked).
 */
final class TaskCenterAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(TaskCenterAgentFactory.class);

    /** Hard read-only co-creation system prompt. */
    static final String SYS_PROMPT =
            "You are a read-only strategy co-creation assistant. You help a user turn a trading idea "
            + "into a declarative StrategySpec Draft by asking ONE highest-impact question at a time. "
            + "You NEVER place, cancel or modify any order; you NEVER compute or fabricate backtest "
            + "metrics (those come only from the deterministic Backtest Runner); you NEVER approve, "
            + "freeze or auto-start any task (that requires human action); you NEVER output credentials, "
            + "account numbers, passwords, AuthCodes, AppIDs or tokens. When a fact comes from the "
            + "local knowledge base you must cite it. If you are unsure, say so and keep it short.";

    private TaskCenterAgentFactory() {}

    /**
     * @param baseUrl       model base URL
     * @param model         model id
     * @param apiKeyEnv     name of the env var holding the API key (never the value here)
     * @param workspaceDir  agentscope workspace dir
     * @param keyConfigured whether the key env var is set (controls live model calls)
     * @param knowledge     the local knowledge service (used by the rag tool)
     * @return a built ReActAgent, or null if it could not be constructed
     */
    static ReActAgent buildTaskCenterAgent(String baseUrl, String model, String apiKeyEnv,
                                           String workspaceDir, boolean keyConfigured,
                                           KnowledgeService knowledge) {
        try {
            ToolkitBuilder toolkit = new ToolkitBuilder();
            toolkit.register(new TaskCenterTools(knowledge));
            String apiKey = keyConfigured ? System.getenv(apiKeyEnv) : "";
            OpenAIChatModel chatModel = new OpenAIChatModel.Builder()
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .apiKey(apiKey == null ? "" : apiKey)
                    .build();
            ReActAgent agent = ReActAgent.builder()
                    .name("task-center-co-creation")
                    .description("Read-only strategy co-creation assistant. Cannot place or cancel orders.")
                    .sysPrompt(SYS_PROMPT)
                    .model(chatModel)
                    .toolkit(toolkit.build())
                    .middleware(new TaskCenterToolGuard())
                    .maxIters(3)
                    .build();
            log.info("Built task-center ReActAgent model={} tools={}",
                    model, toolkit.build().getToolNames());
            return agent;
        } catch (Throwable t) {
            log.error("Failed to build task-center agent: {}", t.toString());
            return null;
        }
    }

    /** Small helper to keep the import surface minimal. */
    private static final class ToolkitBuilder {
        private final io.agentscope.core.tool.Toolkit toolkit =
                new io.agentscope.core.tool.Toolkit();
        void register(Object tools) {
            toolkit.registerTool(tools);
        }
        io.agentscope.core.tool.Toolkit build() {
            return toolkit;
        }
    }
}
