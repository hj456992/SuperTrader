package com.supertrader.demo.team;

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
 * HTTP-level tests for the Module 6 team API, against the real Spring Boot
 * context (RANDOM_PORT). Covers:
 *  - the full contract: GET/POST /teams/members, PATCH /teams/members/{id},
 *    POST /teams/members/{id}/active, GET/PUT /teams/current-actor;
 *  - the auto-created initial OWNER of each workspace;
 *  - server-side RBAC over HTTP (OWNER/ADMIN/TRADER/VIEWER), the uniform error
 *    envelope (400 / 403 / 404 / 409), and LAST_OWNER_PROTECTED;
 *  - strict per-workspace isolation (cross-workspace member id -> 404);
 *  - NO DELETE route, NO trading route anywhere, NO sensitive field in any
 *    response;
 *  - scratch temp files so the real rebuild/.run/ is never touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    TeamStore teamStore;

    @Autowired
    com.supertrader.demo.workspace.WorkspaceStore workspaceStore;

    static final ObjectMapper MAPPER = new ObjectMapper();

    static Path workspaceScratch;
    static Path teamsScratch;

    @DynamicPropertySource
    static void scratchFiles(DynamicPropertyRegistry registry) throws IOException {
        workspaceScratch = Files.createTempFile("simnow-workspaces-teamapi-", ".json");
        registry.add("app.workspaces.file", () -> workspaceScratch.toString());
        Path history = Files.createTempFile("simnow-history-teamapi-", ".json");
        registry.add("app.history.file", () -> history.toString());
        teamsScratch = Files.createTempFile("simnow-teams-teamapi-", ".json");
        registry.add("app.teams.file", () -> teamsScratch.toString());
        Path risk = Files.createTempFile("simnow-risk-teamapi-", ".json");
        registry.add("app.risk-control.file", () -> risk.toString());
    }

    private String base() {
        return "http://localhost:" + port + "/api/v1/teams";
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

    private String ownerId() throws Exception {
        JsonNode members = body(http.getForEntity(base() + "/members", String.class));
        return members.path("members").get(0).path("id").asText();
    }

    private String createMember(String name, String role) throws Exception {
        ResponseEntity<String> r = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"" + name + "\",\"role\":\"" + role + "\"}");
        assertEquals(201, r.getStatusCodeValue());
        return body(r).path("member").path("id").asText();
    }

    private void switchActor(String memberId) throws Exception {
        ResponseEntity<String> r = json(base() + "/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + memberId + "\"}");
        assertEquals(200, r.getStatusCodeValue());
    }

    @BeforeEach
    void resetToFreshState() throws Exception {
        // Fresh team + workspace files: the next read repairs to the
        // per-workspace initial OWNER / default workspace.
        Files.deleteIfExists(teamsScratch);
        Files.deleteIfExists(workspaceScratch);
        workspaceStore.list(); // lazily creates the default workspace
    }

    // ------------------------------------------------------------------ //
    // Initial OWNER + contract basics
    // ------------------------------------------------------------------ //

    @Test
    void defaultWorkspaceGetsOneInitialOwnerOnFirstAccess() throws Exception {
        ResponseEntity<String> r = http.getForEntity(base() + "/members", String.class);
        assertEquals(200, r.getStatusCodeValue());
        JsonNode node = body(r);
        assertEquals("teams.members.v1", node.path("schema").asText());
        assertEquals("default", node.path("workspaceId").asText());
        JsonNode arr = node.path("members");
        assertEquals(1, arr.size());
        assertEquals(TeamStore.ROLE_OWNER, arr.get(0).path("role").asText());
        assertEquals("本地所有者", arr.get(0).path("displayName").asText());
        assertTrue(arr.get(0).path("active").asBoolean());

        ResponseEntity<String> actor = http.getForEntity(base() + "/current-actor", String.class);
        assertEquals(200, actor.getStatusCodeValue());
        JsonNode a = body(actor);
        assertEquals(arr.get(0).path("id").asText(), a.path("actor").path("id").asText());
        assertEquals("default", a.path("workspaceId").asText());
    }

    @Test
    void createMemberReturns201AndAppearsInList() throws Exception {
        String id = createMember("交易员", TeamStore.ROLE_TRADER);
        assertTrue(id.length() > 0);
        JsonNode list = body(http.getForEntity(base() + "/members", String.class));
        assertEquals(2, list.path("members").size());
        assertEquals("default",
                list.path("members").get(1).path("workspaceId").asText());
        assertTrue(list.path("members").get(1).path("active").asBoolean());
    }

    // ------------------------------------------------------------------ //
    // Server-side validation + error envelope
    // ------------------------------------------------------------------ //

    @Test
    void invalidInputsReturn400WithEnvelope() throws Exception {
        ResponseEntity<String> blank = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"   \",\"role\":\"TRADER\"}");
        assertEquals(400, blank.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_INVALID_NAME,
                body(blank).path("error").path("code").asText());

        ResponseEntity<String> badRole = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"某人\",\"role\":\"SUPER\"}");
        assertEquals(400, badRole.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_INVALID_ROLE,
                body(badRole).path("error").path("code").asText());

        ResponseEntity<String> notJson = json(base() + "/members", HttpMethod.POST, "not-json");
        assertEquals(400, notJson.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_INVALID_BODY,
                body(notJson).path("error").path("code").asText());

        ResponseEntity<String> badActive = json(base() + "/members/" + ownerId() + "/active",
                HttpMethod.POST, "{\"active\":\"yes\"}");
        assertEquals(400, badActive.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_INVALID_BODY,
                body(badActive).path("error").path("code").asText());
    }

    @Test
    void ownerCannotCreateOwnerOverHttp() throws Exception {
        ResponseEntity<String> r = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"另一个所有者\",\"role\":\"OWNER\"}");
        assertEquals(403, r.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_FORBIDDEN, body(r).path("error").path("code").asText());
    }

    @Test
    void duplicateMemberReturns409() throws Exception {
        createMember("交易员", TeamStore.ROLE_TRADER);
        ResponseEntity<String> r = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"交易员\",\"role\":\"VIEWER\"}");
        assertEquals(409, r.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_DUPLICATE_MEMBER,
                body(r).path("error").path("code").asText());
    }

    @Test
    void unknownMemberReturns404() throws Exception {
        ResponseEntity<String> role = json(base() + "/members/no-such", HttpMethod.PATCH,
                "{\"role\":\"VIEWER\"}");
        assertEquals(404, role.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_MEMBER_NOT_FOUND,
                body(role).path("error").path("code").asText());
        ResponseEntity<String> active = json(base() + "/members/no-such/active", HttpMethod.POST,
                "{\"active\":false}");
        assertEquals(404, active.getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // RBAC matrix over HTTP
    // ------------------------------------------------------------------ //

    @Test
    void demotingSoleActiveOwnerReturns409() throws Exception {
        String ownerId = ownerId();
        ResponseEntity<String> r = json(base() + "/members/" + ownerId, HttpMethod.PATCH,
                "{\"role\":\"ADMIN\"}");
        assertEquals(409, r.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_LAST_OWNER_PROTECTED,
                body(r).path("error").path("code").asText());
    }

    @Test
    void deactivatingSoleActiveOwnerReturns409() throws Exception {
        String ownerId = ownerId();
        ResponseEntity<String> r = json(base() + "/members/" + ownerId + "/active", HttpMethod.POST,
                "{\"active\":false}");
        assertEquals(409, r.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_LAST_OWNER_PROTECTED,
                body(r).path("error").path("code").asText());
    }

    @Test
    void adminSeesRestrictedSurfaceOverHttp() throws Exception {
        String adminId = createMember("管理员", TeamStore.ROLE_ADMIN);
        switchActor(adminId);

        // ADMIN may create TRADER / VIEWER...
        ResponseEntity<String> ok = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"交易员\",\"role\":\"TRADER\"}");
        assertEquals(201, ok.getStatusCodeValue());
        // ...but NOT ADMIN / OWNER.
        ResponseEntity<String> admin = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"第二管理员\",\"role\":\"ADMIN\"}");
        assertEquals(403, admin.getStatusCodeValue());
        ResponseEntity<String> owner = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"第二所有者\",\"role\":\"OWNER\"}");
        assertEquals(403, owner.getStatusCodeValue());

        // ADMIN may not touch the OWNER at all.
        String ownerId = ownerId();
        ResponseEntity<String> touch = json(base() + "/members/" + ownerId, HttpMethod.PATCH,
                "{\"role\":\"VIEWER\"}");
        assertEquals(403, touch.getStatusCodeValue());
        ResponseEntity<String> deact = json(base() + "/members/" + ownerId + "/active",
                HttpMethod.POST, "{\"active\":false}");
        assertEquals(403, deact.getStatusCodeValue());
    }

    @Test
    void traderHasNoManagementPowerOverHttp() throws Exception {
        String traderId = createMember("交易员", TeamStore.ROLE_TRADER);
        switchActor(traderId);
        ResponseEntity<String> create = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"新成员\",\"role\":\"VIEWER\"}");
        assertEquals(403, create.getStatusCodeValue());
        ResponseEntity<String> role = json(base() + "/members/" + traderId, HttpMethod.PATCH,
                "{\"role\":\"VIEWER\"}");
        assertEquals(403, role.getStatusCodeValue());
        ResponseEntity<String> active = json(base() + "/members/" + traderId + "/active",
                HttpMethod.POST, "{\"active\":false}");
        assertEquals(403, active.getStatusCodeValue());
    }

    @Test
    void roleChangeAndToggleWorkForOwnerOverHttp() throws Exception {
        String traderId = createMember("交易员", TeamStore.ROLE_TRADER);
        ResponseEntity<String> role = json(base() + "/members/" + traderId, HttpMethod.PATCH,
                "{\"role\":\"VIEWER\"}");
        assertEquals(200, role.getStatusCodeValue());
        assertEquals(TeamStore.ROLE_VIEWER, body(role).path("member").path("role").asText());
        ResponseEntity<String> active = json(base() + "/members/" + traderId + "/active",
                HttpMethod.POST, "{\"active\":false}");
        assertEquals(200, active.getStatusCodeValue());
        assertFalse(body(active).path("member").path("active").asBoolean());
        ResponseEntity<String> restore = json(base() + "/members/" + traderId + "/active",
                HttpMethod.POST, "{\"active\":true}");
        assertEquals(200, restore.getStatusCodeValue());
        assertTrue(body(restore).path("member").path("active").asBoolean());
    }

    @Test
    void switchingToInactiveMemberReturns409() throws Exception {
        String traderId = createMember("交易员", TeamStore.ROLE_TRADER);
        json(base() + "/members/" + traderId + "/active", HttpMethod.POST, "{\"active\":false}");
        ResponseEntity<String> r = json(base() + "/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + traderId + "\"}");
        assertEquals(409, r.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_INACTIVE_MEMBER,
                body(r).path("error").path("code").asText());
    }

    // ------------------------------------------------------------------ //
    // Per-workspace isolation over HTTP
    // ------------------------------------------------------------------ //

    @Test
    void switchingWorkspaceSwitchesMembersAndActor() throws Exception {
        createMember("交易员", TeamStore.ROLE_TRADER);
        ResponseEntity<String> created = json(
                "http://localhost:" + port + "/api/v1/workspaces", HttpMethod.POST,
                "{\"name\":\"策略实验室\"}");
        String ws2Id = body(created).path("workspace").path("id").asText();
        json("http://localhost:" + port + "/api/v1/workspaces/current", HttpMethod.PUT,
                "{\"id\":\"" + ws2Id + "\"}");

        // The new workspace has ONLY its own initial OWNER.
        JsonNode members = body(http.getForEntity(base() + "/members", String.class));
        assertEquals(1, members.path("members").size());
        assertEquals(ws2Id, members.path("workspaceId").asText());
        assertEquals(TeamStore.ROLE_OWNER,
                members.path("members").get(0).path("role").asText());
        assertEquals("本地所有者",
                members.path("members").get(0).path("displayName").asText());
        JsonNode actor = body(http.getForEntity(base() + "/current-actor", String.class));
        assertEquals(ws2Id, actor.path("workspaceId").asText());
        assertEquals(members.path("members").get(0).path("id").asText(),
                actor.path("actor").path("id").asText());
    }

    @Test
    void crossWorkspaceMemberIdIsNotFoundOverHttp() throws Exception {
        String traderId = createMember("交易员", TeamStore.ROLE_TRADER);
        ResponseEntity<String> created = json(
                "http://localhost:" + port + "/api/v1/workspaces", HttpMethod.POST,
                "{\"name\":\"策略实验室\"}");
        String ws2Id = body(created).path("workspace").path("id").asText();
        json("http://localhost:" + port + "/api/v1/workspaces/current", HttpMethod.PUT,
                "{\"id\":\"" + ws2Id + "\"}");

        // Trying to touch ws1's member from ws2 is a 404 (deliberate isolation).
        ResponseEntity<String> role = json(base() + "/members/" + traderId, HttpMethod.PATCH,
                "{\"role\":\"VIEWER\"}");
        assertEquals(404, role.getStatusCodeValue());
        assertEquals(TeamApiException.CODE_MEMBER_NOT_FOUND,
                body(role).path("error").path("code").asText());
        ResponseEntity<String> active = json(base() + "/members/" + traderId + "/active",
                HttpMethod.POST, "{\"active\":false}");
        assertEquals(404, active.getStatusCodeValue());
        ResponseEntity<String> actor = json(base() + "/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + traderId + "\"}");
        assertEquals(404, actor.getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // No DELETE route; no trading routes; no sensitive fields
    // ------------------------------------------------------------------ //

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
    void noDeleteRouteExistsForTeams() {
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(info)) {
                if (pattern.startsWith("/api/v1/teams")) {
                    assertFalse(info.getMethodsCondition().getMethods()
                                    .contains(org.springframework.http.HttpMethod.DELETE),
                            "DELETE must never be offered on " + pattern);
                }
            }
        }
    }

    @Test
    void noTradingRoutesRegisteredAnywhere() {
        List<String> patterns = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            patterns.addAll(patternsOf(info));
        }
        assertFalse(patterns.isEmpty(), "routes must be registered");
        // Module 7's /api/v1/trading-accounts is metadata registration (NOT a
        // trading action), so it is an allowed family; the banned tokens below
        // are order/cancel verbs ("trade" removed as too broad — it would false
        // on the legitimate trading-accounts metadata family).
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

    @Test
    void noResponseEverCarriesSensitiveFields() throws Exception {
        createMember("交易员", TeamStore.ROLE_TRADER);
        String[] urls = {base() + "/members", base() + "/current-actor"};
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
        ResponseEntity<String> err = json(base() + "/members", HttpMethod.POST,
                "{\"displayName\":\"   \",\"role\":\"TRADER\"}");
        assertEquals(400, err.getStatusCodeValue());
        String lower = err.getBody().toLowerCase();
        for (String s : sensitive) {
            assertFalse(lower.contains(s));
        }
    }

    @Test
    void memberPayloadKeysAreOnlyNonSensitiveMetadata() throws Exception {
        String id = createMember("交易员", TeamStore.ROLE_TRADER);
        ResponseEntity<String> r = json(base() + "/members/" + id, HttpMethod.PATCH,
                "{\"role\":\"VIEWER\"}");
        Set<String> keys = new java.util.HashSet<>();
        body(r).path("member").fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id", "workspaceId", "displayName", "role",
                "createdAt", "updatedAt", "active"), keys);
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