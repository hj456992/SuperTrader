package com.supertrader.demo.agentscope;

import io.agentscope.core.ReActAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real AgentScope initialization.
 *
 * What "real" means here:
 *  - The AgentScope core classes (io.agentscope.*) are genuinely on the runtime
 *    classpath and are exercised at compile time (we link against ReActAgent,
 *    Toolkit, OpenAIChatModel directly — no reflection).
 *  - On startup we build a {@link ReActAgent} wired to an OpenAI-compatible
 *    model pointed at DeepSeek, with a read-only system prompt, a Toolkit of
 *    ONLY read-only tools, and a {@link ReadOnlyToolGuard} middleware.
 *  - Health reports {@link #initialized()}, which is true only after the agent
 *    object was successfully constructed.
 *
 * The DeepSeek API key is read ONLY from the environment (DEEPSEEK_API_KEY) at
 * runtime and passed straight into the model builder; it is never stored in a
 * field that is logged or serialised.
 */
@Component
public class AgentScopeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeBootstrap.class);

    @Value("${app.agentscope.enabled:true}")
    private boolean enabled;

    @Value("${app.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${app.deepseek.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${app.deepseek.api-key-env:DEEPSEEK_API_KEY}")
    private String apiKeyEnv;

    @Value("${app.agentscope.workspace}")
    private String workspace;

    private String agentscopeVersion = "n/a";
    private volatile boolean initialized = false;
    private volatile String detail = "not initialized";
    private volatile boolean modelConfigured = false;
    private volatile ReActAgent builtAgent;

    @PostConstruct
    public void init() {
        if (!enabled) {
            detail = "disabled by configuration (app.agentscope.enabled=false)";
            log.info("AgentScope bootstrap disabled by config.");
            return;
        }
        try {
            agentscopeVersion = resolveVersionFromPom("n/a");
            String key = System.getenv(apiKeyEnv);
            modelConfigured = key != null && !key.isEmpty();

            this.builtAgent = AgentScopeFactory.buildDiagnosticAgent(
                    deepseekBaseUrl, deepseekModel, apiKeyEnv, workspace, modelConfigured);

            if (this.builtAgent != null) {
                initialized = true;
                detail = "initialized; framework=io.agentscope v" + agentscopeVersion
                        + "; model=" + deepseekModel + "@" + hostOf(deepseekBaseUrl)
                        + "; keyConfigured=" + modelConfigured
                        + "; readOnlyTools=true; guard=ReadOnlyToolGuard";
                log.info("AgentScope initialized: {}", detail);
            } else {
                detail = "AgentScope v" + agentscopeVersion
                        + " on classpath but agent build failed; see logs";
                log.error("AgentScope agent build returned null");
            }
        } catch (Throwable t) {
            detail = "AgentScope init error: " + safeMsg(t);
            log.error("AgentScope initialization failed", t);
        }
    }

    public boolean initialized() { return initialized; }
    public String detail() { return detail; }
    public String version() { return agentscopeVersion; }
    public boolean modelConfigured() { return modelConfigured; }
    public ReActAgent agent() { return builtAgent; }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static String hostOf(String url) {
        try { return new java.net.URI(url).getHost(); }
        catch (Exception e) { return "unknown-host"; }
    }

    private String resolveVersionFromPom(String fallback) {
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/maven/io.agentscope/agentscope-core/pom.properties")) {
            if (in != null) {
                var props = new java.util.Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Exception ignored) { /* fall through */ }
        return fallback;
    }
}
