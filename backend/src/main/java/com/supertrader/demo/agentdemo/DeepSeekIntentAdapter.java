package com.supertrader.demo.agentdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supertrader.demo.taskcenter.IntentInferencePort;
import com.supertrader.demo.taskcenter.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The real DeepSeek structured-intent adapter (Task 3 / design §2.2, §6.2).
 *
 * <p>This is the ONLY real model path of the Demo. It implements
 * {@link IntentInferencePort} and reaches DeepSeek's OpenAI-compatible
 * chat-completions endpoint. The deterministic {@code IntentClassifier} and
 * {@code IntentReconciler} still run first and merge afterwards — rules take
 * priority and the model can never authorize a high-impact action.
 *
 * <p>Hard guarantees:
 * <ul>
 *   <li>the API key is sent ONLY as an {@code Authorization: Bearer ...}
 *       header to the server-configured base URL; it never appears in fields,
 *       {@code toString()}, exceptions, logs, events or the Store;</li>
 *   <li>no client-supplied model parameter is ever accepted — the model and
 *       base URL are server-owned;</li>
 *   <li>the response must match the IntentResult JSON schema; markdown fences
 *       are stripped only when the whole content is a single JSON block;</li>
 *   <li>any refusal-shaped mutation / authorization from the model is dropped
 *       (fail-closed MODEL_OUTPUT_INVALID) so the model can never escalate;</li>
 *   <li>no key ⇒ distinct {@code MODEL_UNAVAILABLE}; timeouts ⇒
 *       {@code MODEL_TIMEOUT}; non-2xx ⇒ {@code MODEL_UNAVAILABLE}.</li>
 * </ul>
 *
 * <p>The system prompt only asks for JSON output — never a chain of thought.
 */
public class DeepSeekIntentAdapter implements IntentInferencePort {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekIntentAdapter.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/agent-demo-intent-system.txt";
    private static final String FALLBACK_SYSTEM_PROMPT =
            "你是结构化意图识别器。只输出一个符合 IntentResult schema 的 JSON 对象，"
                    + "不输出思维链或解释。authorization 永远不得为 CONFIRMED；mutation 永远不得为执行类。";

    private final String baseUrl;
    private final String model;
    private final String apiKeyEnvName;
    private final String apiKey;
    private final int timeoutSeconds;
    private final String systemPrompt;

    /**
     * @param baseUrl       server-configured DeepSeek base URL
     * @param model         server-configured model id
     * @param apiKeyEnvName the environment-variable NAME the key was sourced
     *                      from (stored only as a name, never the value)
     * @param apiKey        the API key value (sent only as a header)
     * @param timeoutSeconds per-call model timeout
     */
    public DeepSeekIntentAdapter(String baseUrl, String model, String apiKeyEnvName,
                                 String apiKey, int timeoutSeconds) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.deepseek.com" : stripTrailingSlash(baseUrl);
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model;
        this.apiKeyEnvName = apiKeyEnvName == null ? "DEEPSEEK_API_KEY" : apiKeyEnvName;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.systemPrompt = loadSystemPrompt();
    }

    @Override
    public boolean modelConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ModelIntent infer(IntentInferenceRequest request) {
        if (!modelConfigured()) {
            return ModelIntent.unavailable();
        }
        String userMessage = sanitize(request.content());
        String body = buildRequestBody(systemPrompt, userMessage, model);
        URI uri = URI.create(baseUrl + "/chat/completions");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .build();
        HttpRequest httpReq = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            return ModelIntent.failed("MODEL_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ModelIntent.failed("MODEL_TIMEOUT");
        } catch (Exception e) {
            log.warn("DeepSeek intent call failed (keyEnv={}): {}",
                    apiKeyEnvName, e.getClass().getSimpleName());
            return ModelIntent.unavailable();
        }
        if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
            return ModelIntent.unavailable();
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return ModelIntent.unavailable();
        }
        String content = extractAssistantContent(resp.body());
        if (content == null || content.isBlank()) {
            return ModelIntent.failed("MODEL_OUTPUT_INVALID");
        }
        content = stripJsonFence(content);
        IntentResult parsed;
        try {
            parsed = parseIntentResult(content);
        } catch (ModelOutputInvalid e) {
            return ModelIntent.failed("MODEL_OUTPUT_INVALID");
        } catch (Exception e) {
            return ModelIntent.failed("MODEL_OUTPUT_INVALID");
        }
        return ModelIntent.of(parsed);
    }

    /** Visible for tests: load the system prompt resource (never the key). */
    static String loadSystemPrompt() {
        try {
            Resource res = new ClassPathResource(SYSTEM_PROMPT_RESOURCE);
            try (var is = res.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return FALLBACK_SYSTEM_PROMPT;
        }
    }

    @Override
    public String toString() {
        // NEVER include the key. The env-var NAME is fine (it is not a secret).
        return "DeepSeekIntentAdapter{baseUrl=" + baseUrl + ", model=" + model
                + ", keyEnv=" + apiKeyEnvName + ", configured=" + modelConfigured() + "}";
    }

    /** Visible for tests: the env-var NAME (not the value). */
    String envName() {
        return apiKeyEnvName;
    }

    // ------------------------------------------------------------------ //
    // Request / response handling
    // ------------------------------------------------------------------ //

    private String buildRequestBody(String systemPrompt, String userMessage, String model) {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", model);
            req.put("temperature", 0.0);
            req.put("response_format", Map.of("type", "json_object"));
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userMessage));
            req.put("messages", messages);
            return M.writeValueAsString(req);
        } catch (Exception e) {
            // Should never happen for a plain map; fall back to a minimal body.
            return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\","
                    + "\"content\":\"" + sanitize(userMessage) + "\"}]}";
        }
    }

    private String extractAssistantContent(String body) {
        try {
            JsonNode root = M.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return null;
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) return null;
            return content.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String stripJsonFence(String content) {
        String c = content.trim();
        if (c.startsWith("```")) {
            int firstNewline = c.indexOf('\n');
            if (firstNewline > 0) c = c.substring(firstNewline + 1);
            int lastFence = c.lastIndexOf("```");
            if (lastFence >= 0) c = c.substring(0, lastFence);
        }
        return c.trim();
    }

    private IntentResult parseIntentResult(String content) {
        JsonNode root;
        try {
            root = M.readTree(content);
        } catch (Exception e) {
            throw new ModelOutputInvalid();
        }
        if (root == null || !root.isObject()) throw new ModelOutputInvalid();

        // Required fields: the model MUST emit labels + speechAct + domain +
        // mutation + authorization + modelConfidence. A missing required field
        // is fail-closed MODEL_OUTPUT_INVALID (we never guess what the model
        // "meant" for a high-impact turn).
        List<String> labels = readStringArray(root.get("labels"));
        if (labels.isEmpty()) throw new ModelOutputInvalid();
        for (String label : labels) {
            if (!IntentResult.ALLOWED_LABELS.contains(label)) {
                throw new ModelOutputInvalid();
            }
        }
        String speechAct = requiredText(root.get("speechAct"), "speechAct");
        String domain = requiredText(root.get("domain"), "domain");
        String mutation = requiredText(root.get("mutation"), "mutation");
        String authorization = requiredText(root.get("authorization"), "authorization");
        if (!root.has("modelConfidence") || root.get("modelConfidence").isNull()) {
            throw new ModelOutputInvalid();
        }
        String targetType = optTextOrNull(root.get("targetType"));
        String targetId = optTextOrNull(root.get("targetId"));

        // Fail-closed: the model can NEVER authorize or execute.
        if (IntentResult.REFUSED_MUTATIONS.contains(mutation)) {
            throw new ModelOutputInvalid();
        }
        if (IntentResult.AUTH_CONFIRMED.equals(authorization)) {
            throw new ModelOutputInvalid();
        }
        if (!isAllowedSpeechAct(speechAct)) speechAct = IntentResult.SPEECH_REQUEST;
        if (!isAllowedDomain(domain)) domain = IntentResult.DOMAIN_GENERAL;

        double modelConfidence = root.path("modelConfidence").asDouble(0.0);
        if (modelConfidence < 0) modelConfidence = 0;
        if (modelConfidence > 1) modelConfidence = 1;

        Map<String, Object> extracted = readObject(root.get("extractedFields"));
        List<String> ambiguities = readStringArray(root.get("ambiguities"));
        List<String> evidenceRefs = readStringArray(root.get("evidenceRefs"));

        String primary = labels.get(0);
        boolean requiresConfirmation = false;
        String nextQuestion = null;
        boolean modelUnavailable = false;

        return new IntentResult(primary, labels, speechAct, domain, targetType, targetId,
                mutation, authorization, modelConfidence, modelConfidence, extracted,
                ambiguities, evidenceRefs, requiresConfirmation, nextQuestion, modelUnavailable);
    }

    private static boolean isAllowedSpeechAct(String s) {
        return IntentResult.SPEECH_REQUEST.equals(s) || IntentResult.SPEECH_INFORM.equals(s)
                || IntentResult.SPEECH_DECIDE.equals(s);
    }

    private static boolean isAllowedDomain(String s) {
        return IntentResult.DOMAIN_STRATEGY.equals(s) || IntentResult.DOMAIN_MARKET.equals(s)
                || IntentResult.DOMAIN_RESEARCH.equals(s) || IntentResult.DOMAIN_EXECUTION.equals(s)
                || IntentResult.DOMAIN_SYSTEM.equals(s) || IntentResult.DOMAIN_GENERAL.equals(s);
    }

    private static List<String> readStringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode n : node) {
            if (n != null && n.isTextual()) out.add(n.asText());
        }
        return out;
    }

    private static Map<String, Object> readObject(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) return;
            if (v.isTextual()) out.put(e.getKey(), v.asText());
            else if (v.isNumber()) out.put(e.getKey(), v.numberValue());
            else if (v.isBoolean()) out.put(e.getKey(), v.booleanValue());
            else if (v.isArray()) {
                List<String> arr = readStringArray(v);
                out.put(e.getKey(), arr);
            } else {
                out.put(e.getKey(), v.toString());
            }
        });
        return out;
    }

    private static String optText(JsonNode node, String def) {
        if (node == null || node.isNull() || node.asText("").isBlank()) return def;
        return node.asText();
    }

    /** A required string field: missing/blank ⇒ MODEL_OUTPUT_INVALID. */
    private static String requiredText(JsonNode node, String name) {
        if (node == null || node.isNull() || node.asText("").isBlank()) {
            throw new ModelOutputInvalid();
        }
        return node.asText();
    }

    private static String optTextOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String t = node.asText();
        return t == null || t.isBlank() || "null".equals(t) ? null : t;
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        // Keep the message intact but drop control characters; credential
        // masking already happened in the guard before this point.
        return text.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "");
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Internal signal for an unparsable / refusal-shaped model output. */
    private static final class ModelOutputInvalid extends RuntimeException {
        ModelOutputInvalid() { super(null, null, false, false); }
    }
}
