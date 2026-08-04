package com.supertrader.demo.taskcenter;

import com.supertrader.demo.team.TeamStore;
import com.supertrader.demo.workspace.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ToolProxy of the Agent Runtime Harness (Module 9). EVERY capability call
 * passes through the same gate, in order:
 * <ol>
 *   <li>capability registration check (unregistered → fail-closed DENIED);</li>
 *   <li>input Schema validation (whitelisted keys per capability);</li>
 *   <li>current-workspace check (the call's workspace must equal the current
 *       workspace — cross-workspace calls are denied);</li>
 *   <li>RBAC check (write capabilities require OWNER / ADMIN / TRADER);</li>
 *   <li>timeout (hard per-call deadline);</li>
 *   <li>output size limit (truncated + flagged);</li>
 *   <li>sensitive-info masking (credential-shaped values are masked before the
 *       output is stored / returned);</li>
 *   <li>trace recording (the caller turns the result into an AgentStep).</li>
 * </ol>
 *
 * <p>The registered handlers are the ONLY executable tools; they are the
 * deterministic implementations wired by the harness (rag.search.local,
 * strategy.validate, ...). A denied call records WHY it was denied and never
 * executes the handler.
 */
@Component
public class ToolProxy {

    private static final Logger log = LoggerFactory.getLogger(ToolProxy.class);

    public static final String ERR_NOT_REGISTERED = "CAPABILITY_NOT_REGISTERED";
    public static final String ERR_INVALID_SCHEMA = "INVALID_SCHEMA";
    public static final String ERR_WORKSPACE_MISMATCH = "WORKSPACE_MISMATCH";
    public static final String ERR_FORBIDDEN = "FORBIDDEN";
    public static final String ERR_TIMEOUT = "TIMEOUT";
    public static final String ERR_HANDLER_FAILED = "HANDLER_FAILED";

    /** Per-capability input-schema: the ONLY allowed argument keys.
     *  {@code simnow.read-only.snapshot} is schema-documented but has NO
     *  registered handler — the Agent Runtime Harness can never invoke it
     *  (refused fail-closed); the only entry point is the explicit risk-centre
     *  endpoint. */
    static final Map<String, Set<String>> SCHEMAS = Map.of(
            CapabilityRegistry.STRATEGY_READ, Set.of("draftId"),
            CapabilityRegistry.STRATEGY_DRAFT_UPDATE, Set.of("field", "value"),
            CapabilityRegistry.STRATEGY_VALIDATE, Set.of("draftId"),
            CapabilityRegistry.RAG_SEARCH_LOCAL, Set.of("query"),
            CapabilityRegistry.BACKTEST_PLAN, Set.of("draftId", "datasetId"),
            CapabilityRegistry.BACKTEST_RESULT_SUMMARIZE, Set.of("runId"),
            CapabilityRegistry.SIMNOW_READ_ONLY_SNAPSHOT, Set.of("instrument"));

    /** A capability call (built by the harness, never by a client). */
    public record ToolCall(String capability, Map<String, String> args,
                           String workspaceId, String actorRole,
                           String runId, String sessionId) {}

    /** The gated result of one call. */
    public record ToolResult(String capability, String inputSummary, String output,
                             boolean ok, boolean truncated, String errorCode,
                             String errorMessage, long durationMs) {}

    @FunctionalInterface
    public interface ToolHandler {
        String handle(Map<String, String> args);
    }

    private final CapabilityRegistry registry;
    private final WorkspaceStore workspaceStore;
    private final Map<String, ToolHandler> handlers = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r, "toolproxy-call");
                t.setDaemon(true);
                return t;
            });

    /** Per-call timeout (seconds). Test-only seam; production uses the
     *  constant. */
    private volatile int timeoutSeconds = BudgetPolicy.DEFAULT_TOOL_TIMEOUT_SECONDS;

    public ToolProxy(CapabilityRegistry registry, WorkspaceStore workspaceStore) {
        this.registry = registry;
        this.workspaceStore = workspaceStore;
    }

    /** TEST-ONLY: shrink the per-call timeout for timeout tests. */
    void setTimeoutSecondsForTests(int seconds) {
        this.timeoutSeconds = Math.max(1, seconds);
    }

    /** Register a deterministic handler for one Agent-callable capability.
     *  Defect E: the SimNow read-only snapshot is a human-endpoint capability
     *  ONLY — registering a handler for it is refused explicitly, so even a
     *  future mistake can never make the Agent invoke it. */
    public void register(String capability, ToolHandler handler) {
        if (!registry.isAgentCallable(capability)) {
            throw new IllegalArgumentException("refusing to register a non-Agent-callable "
                    + "capability (human-endpoint only): " + capability);
        }
        handlers.put(capability, handler);
    }

    /**
     * Execute one capability call through the full gate. NEVER executes an
     * unregistered OR non-Agent-callable capability (fail-closed). Defect E:
     * the SimNow read-only snapshot is refused here too, even if a handler
     * were somehow registered.
     */
    public ToolResult execute(ToolCall call) {
        long started = System.nanoTime();

        // 1. capability registration + Agent-callable check (fail-closed).
        //    The SimNow read-only snapshot is documented but NOT Agent-callable.
        if (!registry.isAgentCallable(call.capability())) {
            return denied(call, ERR_NOT_REGISTERED,
                    "未登记为 Agent 可调用能力，已拒绝调用（fail-closed）", started);
        }

        // 2. input Schema validation.
        String schemaError = validateSchema(call);
        if (schemaError != null) {
            return denied(call, ERR_INVALID_SCHEMA, schemaError, started);
        }

        // 3. current-workspace check (cross-workspace → denied).
        if (call.workspaceId() == null
                || !call.workspaceId().equals(workspaceStore.currentWorkspaceId())) {
            return denied(call, ERR_WORKSPACE_MISMATCH,
                    "工具调用的工作空间与当前工作空间不一致，已拒绝", started);
        }

        // 4. RBAC check (write capabilities need OWNER / ADMIN / TRADER).
        if (CapabilityRegistry.WRITE_CAPABILITIES.contains(call.capability())
                && (call.actorRole() == null || TeamStore.ROLE_VIEWER.equals(call.actorRole()))) {
            return denied(call, ERR_FORBIDDEN,
                    "当前角色（" + call.actorRole() + "）无权调用该能力", started);
        }

        ToolHandler handler = handlers.get(call.capability());
        if (handler == null) {
            return denied(call, ERR_NOT_REGISTERED,
                    "能力已登记但未注册处理器，已拒绝调用（fail-closed）", started);
        }

        // 5. timeout.
        Future<String> future = executor.submit(() -> handler.handle(call.args()));
        String output;
        boolean timedOut = false;
        try {
            output = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            timedOut = true;
            output = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            timedOut = true;
            output = null;
        } catch (ExecutionException e) {
            log.warn("ToolProxy handler failed capability={} cause={}",
                    call.capability(), rootCause(e).getClass().getSimpleName());
            return new ToolResult(call.capability(),
                    maskSensitive(String.valueOf(call.args())), "",
                    false, false, ERR_HANDLER_FAILED,
                    "处理器执行失败：" + rootCause(e).getClass().getSimpleName(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        if (timedOut) {
            return new ToolResult(call.capability(),
                    maskSensitive(String.valueOf(call.args())), "",
                    false, false, ERR_TIMEOUT,
                    "工具调用超时（" + BudgetPolicy.DEFAULT_TOOL_TIMEOUT_SECONDS + "s）",
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }

        // 6 + 7. output size limit + sensitive masking.
        String safe = maskSensitive(output == null ? "" : output);
        boolean truncated = safe.length() > BudgetPolicy.DEFAULT_MAX_OUTPUT_CHARS;
        if (truncated) {
            safe = safe.substring(0, BudgetPolicy.DEFAULT_MAX_OUTPUT_CHARS);
        }
        return new ToolResult(call.capability(),
                maskSensitive(String.valueOf(call.args())), safe,
                true, truncated, null, null,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    /** Visible for tests. */
    String currentWorkspaceId() {
        return workspaceStore.currentWorkspaceId();
    }

    private String validateSchema(ToolCall call) {
        Set<String> allowed = SCHEMAS.get(call.capability());
        if (allowed == null) return "能力没有输入 Schema";
        Map<String, String> args = call.args() == null ? Map.of() : call.args();
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) {
                return "输入包含 Schema 之外的参数：" + key;
            }
        }
        for (String required : allowed) {
            if (!args.containsKey(required)) {
                return "缺少必填参数：" + required;
            }
        }
        return null;
    }

    private ToolResult denied(ToolCall call, String code, String message, long startedNanos) {
        return new ToolResult(call.capability(),
                maskSensitive(String.valueOf(call.args() == null ? Map.of() : call.args())),
                "", false, false, code, message,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    /** Mask credential-shaped values before anything is stored or returned:
     *  {@code field=value} / {@code field: value} with a sensitive field
     *  name → {@code field=***}. The raw value is never kept or logged. */
    static final Pattern SENSITIVE_PAIR = Pattern.compile(
            "(?i)(password|authcode|appid|investorid|brokerid|token|secret|apikey|"
                    + "api_key|privatekey|private_key|credential|accesstoken|refreshtoken)"
                    + "\\s*[=:]\\s*[^\\s,;{}\\[\\]]+");

    public static String maskSensitive(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = SENSITIVE_PAIR.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "=***"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The registered handler names (sorted). Public so the Module 10 tests
     *  can assert that NO handler exists for the read-only SimNow snapshot —
     *  the Agent path can never invoke it. */
    public List<String> registeredCapabilities() {
        return handlers.keySet().stream().sorted().toList();
    }
}
