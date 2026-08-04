package com.supertrader.demo.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the real AgentScope diagnostic agent against the verified 2.0.0 API.
 *
 * The model is an OpenAI-compatible {@link OpenAIChatModel} pointed at the
 * DeepSeek base URL. The API key is read from the named environment variable
 * ONLY and is passed straight into the model builder; it is never stored in a
 * field, never logged, never serialised.
 *
 * The agent is wired with a read-only system prompt and a Toolkit that contains
 * ONLY read-only diagnostic tools, plus a {@link ReadOnlyToolGuard} middleware
 * that denies any tool call not in a hard-coded read-only allowlist. There is
 * deliberately NO order / cancel / trade tool anywhere.
 */
final class AgentScopeFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeFactory.class);

    /** Hard read-only system prompt. Forbids emitting instructions or secrets. */
    static final String READ_ONLY_SYS_PROMPT =
            "You are a read-only diagnostic assistant for a SimNow (CTP) simulation environment. " +
            "You ONLY summarise probe stage results and explain WHY a stage passed, failed, or was skipped. " +
            "You MUST NEVER propose, generate, or output any trading instruction (order insert, order cancel, " +
            "position open/close, strategy dispatch, auto-recovery). You MUST NEVER output credentials, " +
            "account numbers, passwords, AuthCodes, AppIDs or tokens. Keep explanations concise and factual. " +
            "If you are unsure, say the stage outcome and its sanitised reason only.";

    private AgentScopeFactory() {}

    /**
     * @param baseUrl       DeepSeek OpenAI-compatible base URL (e.g. https://api.deepseek.com)
     * @param model         model id (e.g. deepseek-chat)
     * @param apiKeyEnv     name of the env var holding the API key (never the value here)
     * @param workspaceDir  agentscope workspace dir
     * @param keyConfigured whether the key env var is set (controls live model calls)
     * @return a built ReActAgent, or null if it could not be constructed
     */
    static ReActAgent buildDiagnosticAgent(String baseUrl, String model, String apiKeyEnv,
                                           String workspaceDir, boolean keyConfigured) {
        try {
            // 1) Toolkit with ONLY read-only tools.
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new DiagnosticTools());
            // No order/cancel tool is ever registered here.

            // 2) OpenAI-compatible model pointed at DeepSeek. Key from env only.
            String apiKey = keyConfigured ? System.getenv(apiKeyEnv) : "";
            OpenAIChatModel chatModel = new OpenAIChatModel.Builder()
                    .baseUrl(baseUrl)
                    .modelName(model)
                    .apiKey(apiKey == null ? "" : apiKey)
                    .build();

            // 3) Build the agent with the read-only guard middleware.
            ReActAgent agent = ReActAgent.builder()
                    .name("simnow-read-only-diagnostic")
                    .description("Read-only SimNow diagnosis summariser. Cannot place or cancel orders.")
                    .sysPrompt(READ_ONLY_SYS_PROMPT)
                    .model(chatModel)
                    .toolkit(toolkit)
                    .middleware(new ReadOnlyToolGuard())
                    .maxIters(2)   // summarisation needs no long tool loops
                    .build();

            log.info("Built AgentScope ReActAgent name={} model={} tools={}",
                    agent.getClass().getSimpleName(), model, toolkit.getToolNames());
            return agent;
        } catch (Throwable t) {
            log.error("Failed to build AgentScope diagnostic agent: {}", t.toString());
            return null;
        }
    }
}
