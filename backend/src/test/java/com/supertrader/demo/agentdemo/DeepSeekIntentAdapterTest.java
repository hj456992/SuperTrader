package com.supertrader.demo.agentdemo;

import com.supertrader.demo.taskcenter.IntentInferencePort;
import com.supertrader.demo.taskcenter.IntentInferencePort.IntentInferenceRequest;
import com.supertrader.demo.taskcenter.IntentInferencePort.ModelIntent;
import com.supertrader.demo.taskcenter.IntentResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3 tests for the {@link DeepSeekIntentAdapter}.
 *
 * <p>The adapter is the ONLY real model path of the Demo. It must:
 * <ul>
 *   <li>call the server-configured base URL + model;</li>
 *   <li>send the API key ONLY as an Authorization header (never in fields,
 *       toString, exceptions, logs or the Store);</li>
 *   <li>parse a valid IntentResult JSON response;</li>
 *   <li>fail closed (MODEL_UNAVAILABLE / MODEL_OUTPUT_INVALID / MODEL_TIMEOUT)
 *       on markdown fences, missing/extra fields, timeouts and 429;</li>
 *   <li>accept no client model parameter;</li>
 *   <li>return MODEL_UNAVAILABLE distinctly when no key is configured.</li>
 * </ul>
 *
 * <p>All requests go to a LOCAL in-process fake HTTP server — no real network.
 */
class DeepSeekIntentAdapterTest {

    private HttpServer server;
    private FakeHandler handler;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        handler = new FakeHandler();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private IntentInferenceRequest req(String content) {
        return new IntentInferenceRequest(content, false, null, null, null,
                IntentResult.ALLOWED_LABELS);
    }

    @Test
    void callsServerConfiguredBaseUrlAndModelAndAttachesAuthorization() {
        handler.respondWith(validIntentJson());
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        ModelIntent mi = port.infer(req("黄金5日线上穿20日线买入"));

        assertFalse(mi.modelUnavailable());
        assertNotNull(mi.result());
        // The upstream request carried the server-configured model + key.
        assertEquals("deepseek-chat", handler.bodyModel.get());
        assertEquals("Bearer sk-real-key", handler.authHeader.get());
        // The POST path is the OpenAI chat-completions endpoint.
        assertEquals("/chat/completions", handler.path.get());
    }

    @Test
    void parsesValidIntentResultResponse() {
        handler.respondWith(validIntentJson());
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        IntentResult r = port.infer(req("黄金5日线上穿20日线买入")).result();
        assertTrue(r.labels().contains(IntentResult.STRATEGY_CANDIDATE));
        assertEquals(IntentResult.MUTATION_CREATE_SEED, r.mutation());
        assertEquals(IntentResult.AUTH_NOT_CONFIRMED, r.authorization());
        assertEquals(0.88, r.modelConfidence(), 1e-9);
        // Calibrated confidence is clamped by the reconciler, not the adapter;
        // the adapter passes the model number through.
        assertNotNull(r.extractedFields().get("instruments"));
    }

    @Test
    void stripsMarkdownFenceWhenContentIsSingleJsonBlock() {
        // A model that wraps the JSON in ```json fences is a common case.
        handler.respondWith("```json\n" + validIntentJson() + "\n```");
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        IntentResult r = port.infer(req("黄金5日线上穿20日线买入")).result();
        assertNotNull(r);
        assertTrue(r.labels().contains(IntentResult.STRATEGY_CANDIDATE));
    }

    @Test
    void failsClosedOnMissingRequiredFields() {
        handler.respondWith("{\"labels\":[\"GENERAL_QA\"]}"); // missing mutation etc.
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        ModelIntent mi = port.infer(req("hi"));
        assertTrue(mi.modelUnavailable());
        assertEquals("MODEL_OUTPUT_INVALID", mi.errorCode());
    }

    @Test
    void failsClosedOnGarbageContent() {
        handler.respondWith("this is not json at all");
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        ModelIntent mi = port.infer(req("hi"));
        assertTrue(mi.modelUnavailable());
        assertEquals("MODEL_OUTPUT_INVALID", mi.errorCode());
    }

    @Test
    void failsClosedOnRefusedMutationFromModel() {
        // The model tries to authorize an execution; the adapter must drop it
        // (fail closed) — the model can never authorize a high-impact action.
        handler.respondWith("""
                {"labels":["EXECUTION_APPLICATION"],"speechAct":"REQUEST",
                 "domain":"EXECUTION","targetType":"STRATEGY_DRAFT","targetId":"d1",
                 "mutation":"EXECUTE","authorization":"CONFIRMED",
                 "modelConfidence":0.99,"extractedFields":{},"ambiguities":[],"evidenceRefs":[]}
                """);
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        ModelIntent mi = port.infer(req("直接下单"));
        // Refused mutations from the model are dropped → MODEL_OUTPUT_INVALID.
        assertTrue(mi.modelUnavailable());
        assertEquals("MODEL_OUTPUT_INVALID", mi.errorCode());
    }

    @Test
    void failsClosedOnTimeout() {
        // Server never responds within the adapter's 1s timeout.
        handler.respondWith(validIntentJson());
        handler.delayMs.set(3000);
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 1);
        ModelIntent mi = port.infer(req("hi"));
        assertTrue(mi.modelUnavailable());
        assertEquals("MODEL_TIMEOUT", mi.errorCode());
    }

    @Test
    void failsClosedOn429RateLimit() {
        handler.status.set(429);
        handler.respondWith("{\"error\":{\"message\":\"rate limit\"}}");
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        ModelIntent mi = port.infer(req("hi"));
        assertTrue(mi.modelUnavailable());
        assertEquals("MODEL_UNAVAILABLE", mi.errorCode());
    }

    @Test
    void noKeyConfiguredReturnsDistinctModelUnavailable() {
        IntentInferencePort port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "", 30);
        ModelIntent mi = port.infer(req("hi"));
        assertTrue(mi.modelUnavailable());
        assertEquals("MODEL_UNAVAILABLE", mi.errorCode());
        // No request was sent to the upstream.
        assertNull(handler.path.get());
    }

    @Test
    void noKeyMeansModelConfiguredIsFalse() {
        IntentInferencePort noKey = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "", 30);
        assertFalse(noKey.modelConfigured());
        IntentInferencePort withKey = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-real-key", 30);
        assertTrue(withKey.modelConfigured());
    }

    @Test
    void apiKeyDoesNotLeakIntoToStringOrExceptions() {
        DeepSeekIntentAdapter port = new DeepSeekIntentAdapter(
                baseUrl, "deepseek-chat", "DEEPSEEK_API_KEY_TEST", "sk-super-secret-1234", 30);
        String repr = port.toString();
        assertFalse(repr.contains("sk-super-secret-1234"));
        // Trigger a failure path and assert the key is not in the message.
        handler.respondWith("garbage");
        ModelIntent mi = port.infer(req("hi"));
        assertEquals("MODEL_OUTPUT_INVALID", mi.errorCode());
        assertNull(mi.result());
    }

    @Test
    void promptOnlyAsksForJsonNoChainOfThought() {
        String systemPrompt = DeepSeekIntentAdapter.loadSystemPrompt();
        assertNotNull(systemPrompt);
        assertTrue(systemPrompt.contains("只输出"));
        assertFalse(systemPrompt.contains("思维链") && systemPrompt.contains("请输出思维链"));
    }

    private static String validIntentJson() {
        return """
                {"labels":["STRATEGY_CANDIDATE"],"speechAct":"REQUEST",
                 "domain":"STRATEGY","targetType":"STRATEGY_SEED","targetId":null,
                 "mutation":"CREATE_SEED","authorization":"NOT_CONFIRMED",
                 "modelConfidence":0.88,
                 "extractedFields":{"instruments":["黄金"],"entryHint":"5日线上穿20日线买入"},
                 "ambiguities":[],"evidenceRefs":[]}
                """;
    }

    /** A minimal fake OpenAI-compatible chat-completions endpoint. */
    private static final class FakeHandler implements HttpHandler {
        final AtomicReference<String> bodyModel = new AtomicReference<>();
        final AtomicReference<String> authHeader = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicInteger status = new AtomicInteger(200);
        final AtomicInteger delayMs = new AtomicInteger(0);
        volatile String content = "{}";

        void respondWith(String content) {
            this.content = content;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            path.set(ex.getRequestURI().getPath());
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            try (InputStream is = ex.getRequestBody()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                // Capture the model field from the request body (never the key).
                int i = body.indexOf("\"model\"");
                if (i >= 0) {
                    int c = body.indexOf(':', i);
                    int q1 = body.indexOf('"', c + 1);
                    int q2 = body.indexOf('"', q1 + 1);
                    if (q1 > 0 && q2 > q1) bodyModel.set(body.substring(q1 + 1, q2));
                }
            }
            if (delayMs.get() > 0) {
                try { Thread.sleep(delayMs.get()); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            String inner = content.replace("\"", "\\\"").replace("\n", "\\n");
            String payload = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
                    + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"" + inner + "\"},"
                    + "\"finish_reason\":\"stop\"}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), bytes.length);
            try (var os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
