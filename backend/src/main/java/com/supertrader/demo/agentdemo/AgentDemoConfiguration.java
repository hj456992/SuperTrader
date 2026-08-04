package com.supertrader.demo.agentdemo;

import com.supertrader.demo.taskcenter.AgentRuntimeHarness;
import com.supertrader.demo.taskcenter.IntentInferencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Spring wiring for the Agent Demo application layer (Task 8).
 *
 * <p>Registers the atomic {@link AgentDemoStore}, the SSE
 * {@link AgentDemoEventHub}, the {@link AgentDemoRunCoordinator} and the
 * {@link DeepSeekIntentAdapter} (the real {@link IntentInferencePort}). Every
 * model / capability call still goes through the unique
 * {@link AgentRuntimeHarness} — there is no second Agent kernel here.
 *
 * <p>The Demo is local-only by default and gated by
 * {@code app.agent-demo.enabled}. It never starts SimNow, CTP, the gateway,
 * MySQL, Qdrant or Docker.
 */
@Configuration
@ConditionalOnProperty(name = "app.agent-demo.enabled", havingValue = "true",
        matchIfMissing = true)
public class AgentDemoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    public AgentDemoEventHub agentDemoEventHub(AgentDemoStore store) {
        return new AgentDemoEventHub(() -> java.util.List.copyOf(store.snapshot().events()),
                16, 64);
    }

    @Bean(destroyMethod = "close")
    public AgentDemoStore agentDemoStore(@Value("${app.agent-demo.store-file:../.run/agent-demo.json}") String storeFile,
                                         @Value("${app.agent-demo.workspace-id:ws-agent-demo}") String workspaceId,
                                         @Value("${app.agent-demo.owner-id:local-owner-demo}") String ownerId) {
        Path file = Paths.get(storeFile).toAbsolutePath();
        AgentDemoStore store = new AgentDemoStore(file, workspaceId, ownerId);
        store.init();
        log.info("agent-demo store at {}", file);
        return store;
    }

    @Bean
    public DeepSeekIntentAdapter agentDemoIntentAdapter(
            @Value("${app.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${app.deepseek.model:deepseek-chat}") String model,
            @Value("${app.deepseek.api-key-env:DEEPSEEK_API_KEY}") String apiKeyEnv,
            @Value("${app.agent-demo.model-timeout-seconds:30}") int timeoutSeconds) {
        // Read the API key VALUE from the environment variable NAME. The value
        // is never logged, never persisted, only sent as an upstream header.
        String apiKey = "";
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            String v = System.getenv(apiKeyEnv);
            if (v != null) apiKey = v;
        }
        if (apiKey.isBlank()) {
            log.warn("agent-demo: DeepSeek key not configured ({}); MODEL_UNAVAILABLE",
                    apiKeyEnv);
        }
        return new DeepSeekIntentAdapter(baseUrl, model, apiKeyEnv, apiKey, timeoutSeconds);
    }

    @Bean(destroyMethod = "shutdown")
    public AgentDemoRunCoordinator agentDemoRunCoordinator(
            AgentRuntimeHarness harness, AgentDemoStore store,
            AgentDemoEventHub hub, DeepSeekIntentAdapter intentAdapter,
            @Value("${app.agent-demo.max-concurrent-runs:2}") int maxConcurrent,
            @Value("${app.agent-demo.max-run-queue:16}") int maxQueue) {
        return new AgentDemoRunCoordinator(harness, store, hub, intentAdapter,
                maxConcurrent, maxQueue);
    }

    @Bean
    public DemoSensitiveContentGuard agentDemoSensitiveGuard() {
        return new DemoSensitiveContentGuard();
    }

    /**
     * P1-2: register the Demo's server-authoritative fixed workspace
     * ({@code ws-agent-demo}) and make it the CURRENT workspace, so the Demo's
     * ConversationSession / HarnessTurnRequest / ToolCall and the product
     * {@link com.supertrader.demo.taskcenter.ToolProxy} all share the SAME workspace
     * id. This keeps the ToolProxy workspace check ENFORCED (it is NOT disabled)
     * while fixing the prior ERR_WORKSPACE_MISMATCH that rejected RAG / tool
     * calls. Runs once at startup; idempotent.
     */
    @Bean
    public Object agentDemoWorkspaceInitializer(
            com.supertrader.demo.workspace.WorkspaceStore workspaceStore,
            @Value("${app.agent-demo.workspace-id:ws-agent-demo}") String workspaceId) {
        try {
            workspaceStore.ensureWorkspaceAndSelect(workspaceId, "Agent Demo");
            log.info("agent-demo: authoritative workspace {} is current", workspaceId);
        } catch (Throwable t) {
            log.warn("agent-demo: could not ensure workspace {}: {}", workspaceId, t.toString());
        }
        return new Object();
    }
}
