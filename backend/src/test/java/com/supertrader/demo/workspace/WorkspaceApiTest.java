package com.supertrader.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP-level tests for the Module 5 workspace API, against the real Spring
 * Boot context (RANDOM_PORT). Covers:
 *  - the full contract: GET /workspaces, POST /workspaces, GET/PUT
 *    /workspaces/current, PATCH /workspaces/{id};
 *  - server-side input validation (400), unknown id (404), duplicate name (409);
 *  - NO DELETE route on workspaces (at least one workspace always exists);
 *  - responses carry NO sensitive fields and NO trading route exists anywhere;
 *  - the default workspace exists before any write and a scratch temp file is
 *    used so the real rebuild/.run/ is never touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    WorkspaceStore store;

    static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void scratchFiles(DynamicPropertyRegistry registry) throws IOException {
        Path workspaces = Files.createTempFile("simnow-workspaces-api-", ".json");
        registry.add("app.workspaces.file", () -> workspaces.toString());
        Path history = Files.createTempFile("simnow-history-api-", ".json");
        registry.add("app.history.file", () -> history.toString());
        Path teams = Files.createTempFile("simnow-teams-api-", ".json");
        registry.add("app.teams.file", () -> teams.toString());
        Path risk = Files.createTempFile("simnow-risk-wsapi-", ".json");
        registry.add("app.risk-control.file", () -> risk.toString());
    }

    private String base() {
        return "http://localhost:" + port + "/api/v1/workspaces";
    }

    private ResponseEntity<String> json(String url, HttpMethod method, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, method,
                body == null ? null : new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> r) throws Exception {
        return MAPPER.readTree(r.getBody());
    }

    private JsonNode create(String name) throws Exception {
        ResponseEntity<String> r = json(base(), HttpMethod.POST, "{\"name\":\"" + name + "\"}");
        assertEquals(201, r.getStatusCodeValue());
        return body(r).path("workspace");
    }

    @BeforeEach
    void resetToDefaultOnly() throws Exception {
        // Start each test from the repaired default-only state.
        Files.deleteIfExists(store.filePath());
        // The store repairs lazily; first call creates the default workspace.
        store.list();
    }

    // ------------------------------------------------------------------ //
    // Contract basics
    // ------------------------------------------------------------------ //

    @Test
    void listReturnsDefaultWorkspaceBeforeAnyWrite() throws Exception {
        ResponseEntity<String> r = http.getForEntity(base(), String.class);
        assertEquals(200, r.getStatusCodeValue());
        JsonNode node = body(r);
        assertEquals("workspaces.list.v1", node.path("schema").asText());
        assertEquals(Workspace.DEFAULT_ID, node.path("currentWorkspaceId").asText());
        JsonNode arr = node.path("workspaces");
        assertEquals(1, arr.size());
        assertEquals("默认工作空间", arr.get(0).path("name").asText());
    }

    @Test
    void createReturns201AndAppearsInList() throws Exception {
        JsonNode created = create("策略实验室");
        assertTrue(created.path("id").asText().length() > 0);
        assertEquals("策略实验室", created.path("name").asText());
        assertTrue(created.path("createdAt").asText().length() > 0);
        assertTrue(created.path("updatedAt").asText().length() > 0);

        JsonNode list = body(http.getForEntity(base(), String.class));
        assertEquals(2, list.path("workspaces").size());
    }

    @Test
    void currentReturnsDefaultInitiallyAndSwitches() throws Exception {
        JsonNode before = body(http.getForEntity(base() + "/current", String.class));
        assertEquals(Workspace.DEFAULT_ID, before.path("workspace").path("id").asText());

        JsonNode created = create("策略实验室");
        String id = created.path("id").asText();
        ResponseEntity<String> sw = json(base() + "/current", HttpMethod.PUT, "{\"id\":\"" + id + "\"}");
        assertEquals(200, sw.getStatusCodeValue());
        assertEquals(id, body(sw).path("workspace").path("id").asText());

        JsonNode after = body(http.getForEntity(base() + "/current", String.class));
        assertEquals(id, after.path("workspace").path("id").asText());

        JsonNode list = body(http.getForEntity(base(), String.class));
        assertEquals(id, list.path("currentWorkspaceId").asText(),
                "list envelope must reflect the current selection");
    }

    @Test
    void renameWorkspaceById() throws Exception {
        JsonNode created = create("旧名称");
        String id = created.path("id").asText();
        ResponseEntity<String> r = json(base() + "/" + id, HttpMethod.PATCH,
                "{\"name\":\"新名称\"}");
        assertEquals(200, r.getStatusCodeValue());
        assertEquals("新名称", body(r).path("workspace").path("name").asText());
        JsonNode list = body(http.getForEntity(base(), String.class));
        assertEquals("新名称",
                list.path("workspaces").get(1).path("name").asText());
    }

    // ------------------------------------------------------------------ //
    // Server-side validation
    // ------------------------------------------------------------------ //

    @Test
    void createWithBlankNameReturns400() throws Exception {
        ResponseEntity<String> r = json(base(), HttpMethod.POST, "{\"name\":\"   \"}");
        assertEquals(400, r.getStatusCodeValue());
        assertEquals(WorkspaceApiException.CODE_INVALID_NAME, body(r).path("error").path("code").asText());
    }

    @Test
    void createWithOverlongNameReturns400() throws Exception {
        String longName = "a".repeat(WorkspaceStore.MAX_NAME_LENGTH + 1);
        ResponseEntity<String> r = json(base(), HttpMethod.POST,
                "{\"name\":\"" + longName + "\"}");
        assertEquals(400, r.getStatusCodeValue());
    }

    @Test
    void createWithDuplicateNameReturns409() throws Exception {
        create("策略实验室");
        ResponseEntity<String> r = json(base(), HttpMethod.POST, "{\"name\":\"策略实验室\"}");
        assertEquals(409, r.getStatusCodeValue());
        assertEquals(WorkspaceApiException.CODE_DUPLICATE_NAME, body(r).path("error").path("code").asText());
        // The failed create added nothing.
        JsonNode list = body(http.getForEntity(base(), String.class));
        assertEquals(2, list.path("workspaces").size());
    }

    @Test
    void createWithUnreadableBodyReturns400() throws Exception {
        ResponseEntity<String> r = json(base(), HttpMethod.POST, "not-json");
        assertEquals(400, r.getStatusCodeValue());
        assertEquals(WorkspaceApiException.CODE_INVALID_BODY, body(r).path("error").path("code").asText());
    }

    @Test
    void switchToUnknownIdReturns404() throws Exception {
        ResponseEntity<String> r = json(base() + "/current", HttpMethod.PUT, "{\"id\":\"no-such\"}");
        assertEquals(404, r.getStatusCodeValue());
        assertEquals(WorkspaceApiException.CODE_WORKSPACE_NOT_FOUND,
                body(r).path("error").path("code").asText());
    }

    @Test
    void switchToBlankIdReturns400() throws Exception {
        ResponseEntity<String> r = json(base() + "/current", HttpMethod.PUT, "{\"id\":\"\"}");
        assertEquals(400, r.getStatusCodeValue());
        assertEquals(WorkspaceApiException.CODE_INVALID_ID, body(r).path("error").path("code").asText());
    }

    @Test
    void renameUnknownIdReturns404() throws Exception {
        ResponseEntity<String> r = json(base() + "/no-such", HttpMethod.PATCH, "{\"name\":\"x\"}");
        assertEquals(404, r.getStatusCodeValue());
    }

    @Test
    void renameToDuplicateReturns409AndKeepsOriginalName() throws Exception {
        JsonNode a = create("A");
        create("B");
        ResponseEntity<String> r = json(base() + "/" + a.path("id").asText(), HttpMethod.PATCH,
                "{\"name\":\"B\"}");
        assertEquals(409, r.getStatusCodeValue());
        JsonNode list = body(http.getForEntity(base(), String.class));
        assertEquals("A", list.path("workspaces").get(1).path("name").asText());
    }

    @Test
    void renameWithBlankNameReturns400() throws Exception {
        JsonNode created = create("A");
        ResponseEntity<String> r = json(base() + "/" + created.path("id").asText(),
                HttpMethod.PATCH, "{\"name\":\"  \"}");
        assertEquals(400, r.getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // No delete route; at least one workspace always exists
    // ------------------------------------------------------------------ //

    /** Patterns of a mapping — Spring 6.1 may expose either condition. */
    private static List<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            List<String> out = new ArrayList<>();
            for (org.springframework.web.util.pattern.PathPattern p :
                    info.getPathPatternsCondition().getPatterns()) {
                out.add(p.getPatternString());
            }
            return out;
        }
        if (info.getPatternsCondition() != null) {
            return new ArrayList<>(info.getPatternsCondition().getPatterns());
        }
        return List.of();
    }

    @Test
    void noDeleteRouteExistsForWorkspaces() {
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(info)) {
                if (pattern.startsWith("/api/v1/workspaces")) {
                    assertFalse(info.getMethodsCondition().getMethods()
                                    .contains(org.springframework.http.HttpMethod.DELETE),
                            "DELETE must never be offered on " + pattern);
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    // No trading routes anywhere
    // ------------------------------------------------------------------ //

    @Test
    void noTradingRoutesRegisteredAnywhere() {
        // Every registered route pattern must be read-only workspace / diagnosis
        // / health. None may carry a trading-shaped token.
        List<String> patterns = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            patterns.addAll(patternsOf(info));
        }
        assertFalse(patterns.isEmpty(), "routes must be registered");
        // Module 7's /api/v1/trading-accounts is metadata registration (NOT a
        // trading action), so it is an allowed family; the banned tokens below
        // are order/cancel verbs. "trade" is matched only on non-accounts paths
        // so the legitimate trading-accounts metadata family is not a false hit.
        List<String> banned = List.of(
                "order", "cancel", "insert", "action",
                "quote", "hedge", "parked", "offset", "comb", "spd",
                "position", "execution");
        // Module 9's /api/v1/agent* / /api/v1/strategy-drafts / /api/v1/tasks
        // families are the LOCAL task-center (sessions / human approvals /
        // local backtest tasks) — they are NOT trading routes, so they are
        // excluded from the token scan exactly like the trading-accounts
        // metadata family; the banned tokens below are order/cancel/execution
        // verbs.
        for (String p : patterns) {
            String lower = p.toLowerCase();
            if (isModule9Family(p) || isRiskFamily(p)) continue;
            for (String token : banned) {
                assertFalse(lower.contains(token),
                        "trading-shaped route token '" + token + "' in " + p);
            }
        }
        // The exact allowed surface (defensive): nothing beyond these families.
        for (String p : patterns) {
            boolean allowed = p.equals("/api/v1/health")
                    || p.startsWith("/api/v1/simnow")
                    || p.startsWith("/api/v1/workspaces")
                    || p.startsWith("/api/v1/teams")
                    || p.startsWith("/api/v1/trading-accounts")
                    || p.startsWith("/api/v1/strategies")
                    || isModule9Family(p)
                    || isRiskFamily(p)
                    || p.equals("/error");
            assertTrue(allowed, "unexpected route registered: " + p);
        }
    }

    // ------------------------------------------------------------------ //
    // No sensitive fields in any response
    // ------------------------------------------------------------------ //

    @Test
    void noResponseEverCarriesSensitiveFields() throws Exception {
        create("策略实验室");
        String[] urls = {
                base(),
                base() + "/current",
        };
        Set<String> sensitive = Set.of("password", "authcode", "appid", "token", "secret",
                "apikey", "key", "credential", "privatekey");
        for (String url : urls) {
            ResponseEntity<String> r = http.getForEntity(url, String.class);
            assertEquals(200, r.getStatusCodeValue());
            String lower = r.getBody().toLowerCase();
            for (String s : sensitive) {
                assertFalse(lower.contains(s), "sensitive field '" + s + "' leaked in " + url);
            }
        }
        // Error responses are equally clean.
        ResponseEntity<String> err = json(base(), HttpMethod.POST, "{\"name\":\"   \"}");
        assertEquals(400, err.getStatusCodeValue());
        String lower = err.getBody().toLowerCase();
        for (String s : sensitive) {
            assertFalse(lower.contains(s));
        }
    }

    @Test
    void workspacePayloadKeysAreOnlyNonSensitiveMetadata() throws Exception {
        JsonNode created = create("策略实验室");
        Set<String> keys = new java.util.HashSet<>();
        created.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id", "name", "createdAt", "updatedAt"), keys);
    }

    /** Module 9 task-center route families (sessions / drafts / approvals /
     *  local backtest tasks). They are metadata + human-gated local runs,
     *  NOT trading routes, so they are allowed families for the token scan. */
    private static boolean isModule9Family(String p) {
        return p.startsWith("/api/v1/agent")
                || p.startsWith("/api/v1/strategy-drafts")
                || p.startsWith("/api/v1/tasks")
                || p.startsWith("/api/v1/agent-runs")
                || p.startsWith("/api/v1/backtest-runs");
    }

    /** Module 10: /api/v1/risk** is the controlled-evolution / risk-centre
     *  family (offline candidates, human promote/reject, champion rollback,
     *  read-only derivations) — NOT a trading surface; its own hard trading
     *  tokens are scanned by RiskApiTest. */
    private static boolean isRiskFamily(String p) {
        return p.startsWith("/api/v1/risk");
    }

}