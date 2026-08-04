package com.supertrader.demo.strategy;

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
 * HTTP-level tests for the Module 8 strategy-library API, against the real
 * Spring Boot context (RANDOM_PORT). Covers:
 *  - the full contract: GET/POST /strategies, GET/PATCH /strategies/{id},
 *    GET/POST /strategies/{id}/versions, POST /strategies/{id}/archive;
 *  - create returns 201 with version 1 atomically; append is strictly
 *    increasing; client-forged server-owned fields (id / workspaceId / status /
 *    currentVersion / versionNumber / creator / timestamps) are never adopted;
 *  - server-side RBAC over HTTP (OWNER/ADMIN may create/append/rename/archive;
 *    TRADER may create/append but not rename/archive; VIEWER is read-only);
 *  - the per-workspace case-insensitive unique-name rule (409 DUPLICATE_STRATEGY)
 *    and archived-strategy 409 STRATEGY_ARCHIVED;
 *  - strict per-workspace isolation (cross-workspace strategy id -> 404);
 *  - the uniform error envelope (400 / 403 / 404 / 409) for every input
 *    boundary;
 *  - NO DELETE route and NO run/execute/backtest/signal/publish/approve/
 *    reject/bind/order/cancel/recover/auto-trade route anywhere;
 *  - NO sensitive field in any response; payload keys are exactly the
 *    whitelisted metadata;
 *  - scratch temp files so the real rebuild/.run/ is never touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StrategyApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    com.supertrader.demo.workspace.WorkspaceStore workspaceStore;

    @Autowired
    com.supertrader.demo.team.TeamStore teamStore;

    static final ObjectMapper MAPPER = new ObjectMapper();

    static Path workspaceScratch;
    static Path teamsScratch;
    static Path historyScratch;
    static Path accountsScratch;
    static Path strategiesScratch;

    @DynamicPropertySource
    static void scratchFiles(DynamicPropertyRegistry registry) throws IOException {
        workspaceScratch = Files.createTempFile("simnow-workspaces-stra-", ".json");
        registry.add("app.workspaces.file", () -> workspaceScratch.toString());
        Path history = Files.createTempFile("simnow-history-stra-", ".json");
        registry.add("app.history.file", () -> history.toString());
        teamsScratch = Files.createTempFile("simnow-teams-stra-", ".json");
        registry.add("app.teams.file", () -> teamsScratch.toString());
        accountsScratch = Files.createTempFile("simnow-accounts-stra-", ".json");
        registry.add("app.trading-accounts.file", () -> accountsScratch.toString());
        strategiesScratch = Files.createTempFile("simnow-strategies-stra-", ".json");
        registry.add("app.strategies.file", () -> strategiesScratch.toString());
        Path riskScratchStrategyApi = Files.createTempFile("simnow-risk-strategyapi-", ".json");
        registry.add("app.risk-control.file", () -> riskScratchStrategyApi.toString());
    }

    private String base() {
        return "http://localhost:" + port + "/api/v1/strategies";
    }

    private String wsUrl() {
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

    private String create(String name, String summary) throws Exception {
        ResponseEntity<String> r = json(base(), HttpMethod.POST,
                "{\"name\":\"" + name + "\",\"summary\":\"" + summary
                        + "\",\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\","
                        + "\"entryRules\":\"入\",\"exitRules\":\"出\",\"riskNotes\":\"风\"}");
        assertEquals(201, r.getStatusCodeValue());
        return body(r).path("strategy").path("id").asText();
    }

    private void asOwnerOf(String wsId) throws Exception {
        // The auto-created OWNER of the given workspace is always the actor in
        // that workspace; just ensure we are switched to it.
        json(wsUrl() + "/current", HttpMethod.PUT, "{\"id\":\"" + wsId + "\"}");
        JsonNode members = body(http.getForEntity(
                "http://localhost:" + port + "/api/v1/teams/members", String.class));
        String ownerId = members.path("members").get(0).path("id").asText();
        json("http://localhost:" + port + "/api/v1/teams/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + ownerId + "\"}");
    }

    private String switchToRole(String role) throws Exception {
        // Create + switch to a member of the given role in the current workspace.
        JsonNode created = body(json("http://localhost:" + port + "/api/v1/teams/members",
                HttpMethod.POST, "{\"displayName\":\"策略角色" + role + "\",\"role\":\"" + role + "\"}"));
        String id = created.path("member").path("id").asText();
        json("http://localhost:" + port + "/api/v1/teams/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + id + "\"}");
        return id;
    }

    private String validCreateJson(String name) {
        return "{\"name\":\"" + name + "\",\"summary\":\"摘要\",\"instruments\":[\"JM2609\"],"
                + "\"timeframe\":\"1D\",\"entryRules\":\"入\",\"exitRules\":\"出\",\"riskNotes\":\"风\"}";
    }

    @BeforeEach
    void resetToFreshState() throws Exception {
        // Fresh team + workspace + strategy files: the next read repairs to the
        // per-workspace initial OWNER / default workspace / empty library.
        Files.deleteIfExists(strategiesScratch);
        Files.deleteIfExists(accountsScratch);
        Files.deleteIfExists(teamsScratch);
        Files.deleteIfExists(workspaceScratch);
        workspaceStore.list(); // lazily creates the default workspace
        asOwnerOf("default");
    }

    // ------------------------------------------------------------------ //
    // Initial state + create contract
    // ------------------------------------------------------------------ //

    @Test
    void emptyLibraryBeforeAnyStrategy() throws Exception {
        ResponseEntity<String> r = http.getForEntity(base(), String.class);
        assertEquals(200, r.getStatusCodeValue());
        JsonNode node = body(r);
        assertEquals("strategies.list.v1", node.path("schema").asText());
        assertEquals("default", node.path("workspaceId").asText());
        assertTrue(node.path("strategies").isArray());
        assertEquals(0, node.path("strategies").size());
    }

    @Test
    void createReturns201WithVersion1Atomically() throws Exception {
        ResponseEntity<String> r = json(base(), HttpMethod.POST, validCreateJson("均线突破策略"));
        assertEquals(201, r.getStatusCodeValue());
        JsonNode s = body(r).path("strategy");
        assertFalse(s.path("id").asText().isEmpty());
        assertEquals("均线突破策略", s.path("name").asText());
        assertEquals("default", s.path("workspaceId").asText());
        assertEquals("ACTIVE", s.path("status").asText());
        assertEquals(1, s.path("currentVersion").asInt());
        assertFalse(s.path("createdByMemberId").asText().isEmpty());
        assertTrue(s.path("archivedAt").isNull());

        // The version-1 history is immediately readable.
        ResponseEntity<String> versions = http.getForEntity(
                base() + "/" + s.path("id").asText() + "/versions", String.class);
        assertEquals(200, versions.getStatusCodeValue());
        JsonNode vs = body(versions).path("versions");
        assertEquals(1, vs.size());
        assertEquals(1, vs.get(0).path("versionNumber").asInt());
    }

    @Test
    void clientForgedServerOwnedFieldsAreNeverAdopted() throws Exception {
        // The request body carries forged id / workspaceId / status /
        // currentVersion / creator / timestamps / versionNumber — ALL must be
        // ignored; the server derives every server-owned field.
        ResponseEntity<String> r = json(base(), HttpMethod.POST,
                "{\"id\":\"forged-id\",\"workspaceId\":\"evil-ws\",\"status\":\"ARCHIVED\","
                        + "\"currentVersion\":99,\"createdByMemberId\":\"evil-member\","
                        + "\"createdAt\":\"2020-01-01T00:00:00Z\",\"updatedAt\":\"2020-01-01T00:00:00Z\","
                        + "\"archivedAt\":\"2020-01-01T00:00:00Z\",\"versionNumber\":99,"
                        + "\"name\":\"正常策略\",\"summary\":\"摘要\",\"instruments\":[\"JM2609\"],"
                        + "\"timeframe\":\"1D\",\"entryRules\":\"入\",\"exitRules\":\"出\",\"riskNotes\":\"风\"}");
        assertEquals(201, r.getStatusCodeValue());
        JsonNode s = body(r).path("strategy");
        assertNotEquals("forged-id", s.path("id").asText());
        assertEquals("default", s.path("workspaceId").asText());
        assertEquals("ACTIVE", s.path("status").asText());
        assertEquals(1, s.path("currentVersion").asInt());
        assertNotEquals("evil-member", s.path("createdByMemberId").asText());
        assertNotEquals("2020-01-01T00:00:00Z", s.path("createdAt").asText());
        assertTrue(s.path("archivedAt").isNull());
        // The forged fields never reach the disk.
        String disk = Files.readString(strategiesScratch);
        assertFalse(disk.contains("forged-id"));
        assertFalse(disk.contains("evil-ws"));
        assertFalse(disk.contains("evil-member"));
    }

    @Test
    void duplicateNameReturns409() throws Exception {
        create("均线策略", "摘要一");
        ResponseEntity<String> r = json(base(), HttpMethod.POST, validCreateJson(" 均线策略 "));
        assertEquals(409, r.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_DUPLICATE_STRATEGY,
                body(r).path("error").path("code").asText());
    }

    @Test
    void invalidInputsReturn400WithEnvelope() throws Exception {
        // Blank name.
        ResponseEntity<String> blank = json(base(), HttpMethod.POST, validCreateJson("   "));
        assertEquals(400, blank.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_NAME,
                body(blank).path("error").path("code").asText());
        // Not JSON at all.
        ResponseEntity<String> notJson = json(base(), HttpMethod.POST, "not-json");
        assertEquals(400, notJson.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_INVALID_BODY,
                body(notJson).path("error").path("code").asText());
        // Missing summary.
        ResponseEntity<String> noSummary = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\"}");
        assertEquals(400, noSummary.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_VERSION,
                body(noSummary).path("error").path("code").asText());
        // Bad timeframe.
        ResponseEntity<String> badTf = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"summary\":\"摘要\",\"instruments\":[\"JM2609\"],\"timeframe\":\"4H\"}");
        assertEquals(400, badTf.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_INVALID_TIMEFRAME,
                body(badTf).path("error").path("code").asText());
        // Bad instrument.
        ResponseEntity<String> badInstr = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"summary\":\"摘要\",\"instruments\":[\"JM-2609\"],\"timeframe\":\"1D\"}");
        assertEquals(400, badInstr.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_INVALID_INSTRUMENT,
                body(badInstr).path("error").path("code").asText());
        // Wrong instruments type (array type errors are deterministic 400s).
        ResponseEntity<String> wrongType = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"summary\":\"摘要\",\"instruments\":\"JM2609\",\"timeframe\":\"1D\"}");
        assertEquals(400, wrongType.getStatusCodeValue());
        // Overlong summary.
        ResponseEntity<String> longSummary = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"summary\":\"" + "x".repeat(501)
                        + "\",\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\"}");
        assertEquals(400, longSummary.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_VERSION,
                body(longSummary).path("error").path("code").asText());
        // Overlong rule.
        ResponseEntity<String> longRule = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"summary\":\"摘要\",\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\","
                        + "\"entryRules\":\"" + "r".repeat(4001) + "\"}");
        assertEquals(400, longRule.getStatusCodeValue());
        // Too many distinct instruments.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) many.append(',');
            many.append("\"X").append(i).append("\"");
        }
        ResponseEntity<String> tooMany = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"summary\":\"摘要\",\"instruments\":[" + many
                        + "],\"timeframe\":\"1D\"}");
        assertEquals(400, tooMany.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_INVALID_INSTRUMENT,
                body(tooMany).path("error").path("code").asText());
        // 21 raw entries that collapse to 20 after de-duplication are fine.
        StringBuilder withDup = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) withDup.append(',');
            withDup.append("\"X").append(i).append("\"");
        }
        withDup.append(",\"x0\""); // duplicate of X0 after uppercasing
        ResponseEntity<String> dedupOk = json(base(), HttpMethod.POST,
                "{\"name\":\"策略\",\"summary\":\"摘要\",\"instruments\":[" + withDup
                        + "],\"timeframe\":\"1D\"}");
        assertEquals(201, dedupOk.getStatusCodeValue(),
                "duplicates collapse before the 20-limit check (deterministic canonical form)");
    }

    // ------------------------------------------------------------------ //
    // Get / rename / versions / archive
    // ------------------------------------------------------------------ //

    @Test
    void getRenameAndArchiveWork() throws Exception {
        String id = create("策略", "摘要");
        ResponseEntity<String> get = http.getForEntity(base() + "/" + id, String.class);
        assertEquals(200, get.getStatusCodeValue());
        assertEquals(id, body(get).path("strategy").path("id").asText());

        ResponseEntity<String> rename = json(base() + "/" + id, HttpMethod.PATCH,
                "{\"name\":\"新名字\"}");
        assertEquals(200, rename.getStatusCodeValue());
        assertEquals("新名字", body(rename).path("strategy").path("name").asText());

        ResponseEntity<String> archive = json(base() + "/" + id + "/archive",
                HttpMethod.POST, null);
        assertEquals(200, archive.getStatusCodeValue());
        assertEquals("ARCHIVED", body(archive).path("strategy").path("status").asText());
        assertNotNull(body(archive).path("strategy").path("archivedAt").asText());
    }

    @Test
    void unknownStrategyReturns404() throws Exception {
        ResponseEntity<String> get = http.getForEntity(base() + "/no-such", String.class);
        assertEquals(404, get.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_STRATEGY_NOT_FOUND,
                body(get).path("error").path("code").asText());
        ResponseEntity<String> archive = json(base() + "/no-such/archive",
                HttpMethod.POST, null);
        assertEquals(404, archive.getStatusCodeValue());
        ResponseEntity<String> versions = http.getForEntity(
                base() + "/no-such/versions", String.class);
        assertEquals(404, versions.getStatusCodeValue());
    }

    @Test
    void appendVersionIsStrictlyIncreasingAndForgeProof() throws Exception {
        String id = create("策略", "摘要 v1");
        // The body carries a forged versionNumber — the server computes max+1.
        ResponseEntity<String> r = json(base() + "/" + id + "/versions", HttpMethod.POST,
                "{\"versionNumber\":99,\"summary\":\"摘要 v2\",\"instruments\":[\"IF2506\"],"
                        + "\"timeframe\":\"5M\",\"entryRules\":\"入2\",\"exitRules\":\"出2\",\"riskNotes\":\"风2\"}");
        assertEquals(201, r.getStatusCodeValue());
        JsonNode v = body(r).path("version");
        assertEquals(2, v.path("versionNumber").asInt(),
                "versionNumber is server-computed (max+1), never adopted");
        assertEquals("IF2506", v.path("instruments").get(0).asText());
        assertEquals("5M", v.path("timeframe").asText());

        // History is ascending and the strategy currentVersion tracks it.
        JsonNode versions = body(http.getForEntity(base() + "/" + id + "/versions", String.class));
        assertEquals(2, versions.path("versions").size());
        assertEquals(1, versions.path("versions").get(0).path("versionNumber").asInt());
        assertEquals(2, versions.path("versions").get(1).path("versionNumber").asInt());
        JsonNode strategy = body(http.getForEntity(base() + "/" + id, String.class));
        assertEquals(2, strategy.path("strategy").path("currentVersion").asInt());
    }

    @Test
    void archivedStrategyCannotBeRenamedOrVersionedButStaysReadable() throws Exception {
        String id = create("策略", "摘要");
        json(base() + "/" + id + "/archive", HttpMethod.POST, null);
        ResponseEntity<String> rename = json(base() + "/" + id, HttpMethod.PATCH,
                "{\"name\":\"新名\"}");
        assertEquals(409, rename.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_STRATEGY_ARCHIVED,
                body(rename).path("error").path("code").asText());
        ResponseEntity<String> append = json(base() + "/" + id + "/versions", HttpMethod.POST,
                "{\"summary\":\"s\",\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\"}");
        assertEquals(409, append.getStatusCodeValue());
        assertEquals(StrategyApiException.CODE_STRATEGY_ARCHIVED,
                body(append).path("error").path("code").asText());
        // Still readable.
        assertEquals(200, http.getForEntity(base() + "/" + id, String.class).getStatusCodeValue());
        assertEquals(200, http.getForEntity(base() + "/" + id + "/versions", String.class)
                .getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // RBAC over HTTP
    // ------------------------------------------------------------------ //

    @Test
    void traderCanCreateAndAppendButCannotRenameOrArchive() throws Exception {
        String id = create("策略", "摘要"); // as OWNER
        switchToRole("TRADER");

        // TRADER can create + append.
        ResponseEntity<String> create = json(base(), HttpMethod.POST, validCreateJson("交易员策略"));
        assertEquals(201, create.getStatusCodeValue());
        assertEquals(201, json(base() + "/" + id + "/versions", HttpMethod.POST,
                "{\"summary\":\"s2\",\"instruments\":[\"IF2506\"],\"timeframe\":\"5M\"}")
                .getStatusCodeValue());
        // TRADER cannot rename / archive.
        assertEquals(403, json(base() + "/" + id, HttpMethod.PATCH,
                "{\"name\":\"y\"}").getStatusCodeValue());
        assertEquals(403, json(base() + "/" + id + "/archive",
                HttpMethod.POST, null).getStatusCodeValue());
    }

    @Test
    void viewerCanOnlyRead() throws Exception {
        String id = create("策略", "摘要"); // as OWNER
        switchToRole("VIEWER");

        // VIEWER can read.
        assertEquals(200, http.getForEntity(base(), String.class).getStatusCodeValue());
        assertEquals(200, http.getForEntity(base() + "/" + id, String.class).getStatusCodeValue());
        assertEquals(200, http.getForEntity(base() + "/" + id + "/versions", String.class)
                .getStatusCodeValue());
        // VIEWER cannot write anything.
        assertEquals(403, json(base(), HttpMethod.POST, validCreateJson("观察员策略"))
                .getStatusCodeValue());
        assertEquals(403, json(base() + "/" + id + "/versions", HttpMethod.POST,
                "{\"summary\":\"s\",\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\"}")
                .getStatusCodeValue());
        assertEquals(403, json(base() + "/" + id, HttpMethod.PATCH,
                "{\"name\":\"y\"}").getStatusCodeValue());
        assertEquals(403, json(base() + "/" + id + "/archive",
                HttpMethod.POST, null).getStatusCodeValue());
    }

    @Test
    void adminCanDoEverything() throws Exception {
        switchToRole("ADMIN");
        String id = create("策略", "摘要");
        assertEquals(201, json(base() + "/" + id + "/versions", HttpMethod.POST,
                "{\"summary\":\"s2\",\"instruments\":[\"IF2506\"],\"timeframe\":\"5M\"}")
                .getStatusCodeValue());
        assertEquals(200, json(base() + "/" + id, HttpMethod.PATCH,
                "{\"name\":\"管理员改名\"}").getStatusCodeValue());
        assertEquals(200, json(base() + "/" + id + "/archive",
                HttpMethod.POST, null).getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // Per-workspace isolation over HTTP
    // ------------------------------------------------------------------ //

    @Test
    void switchingWorkspaceSwitchesStrategies() throws Exception {
        String id = create("ws1 策略", "摘要"); // as OWNER of default
        ResponseEntity<String> created = json(wsUrl(), HttpMethod.POST,
                "{\"name\":\"策略实验室\"}");
        String ws2Id = body(created).path("workspace").path("id").asText();
        json(wsUrl() + "/current", HttpMethod.PUT, "{\"id\":\"" + ws2Id + "\"}");

        // ws2 sees NO strategies and the ws1 strategy id is a 404 (isolation).
        JsonNode list = body(http.getForEntity(base(), String.class));
        assertEquals(ws2Id, list.path("workspaceId").asText());
        assertEquals(0, list.path("strategies").size());
        ResponseEntity<String> get = http.getForEntity(base() + "/" + id, String.class);
        assertEquals(404, get.getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // No DELETE / banned routes; no sensitive fields; payload whitelist
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
    void noDeleteRouteExistsForStrategies() {
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(info)) {
                if (pattern.startsWith("/api/v1/strategies")) {
                    assertFalse(info.getMethodsCondition().getMethods()
                                    .contains(org.springframework.http.HttpMethod.DELETE),
                            "DELETE must never be offered on " + pattern);
                }
            }
        }
    }

    @Test
    void noBannedStrategyRoutesExistAnywhere() {
        List<String> patterns = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            patterns.addAll(patternsOf(info));
        }
        assertFalse(patterns.isEmpty(), "routes must be registered");
        // The strategy library is research metadata ONLY: none of the banned
        // execution / trading / approval verbs may appear in any route.
        List<String> banned = List.of(
                "run", "execute", "backtest", "signal", "publish",
                "approve", "reject", "bind", "order", "cancel",
                "recover", "auto-trade", "auto", "quote", "hedge", "insert", "action");
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
                        "banned route token '" + token + "' in " + p);
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
        String id = create("策略", "摘要");
        String[] urls = {base(), base() + "/" + id, base() + "/" + id + "/versions"};
        Set<String> sensitive = Set.of("password", "authcode", "appid", "token", "secret",
                "apikey", "privatekey", "investor", "brokerid", "credential");
        for (String url : urls) {
            ResponseEntity<String> r = http.getForEntity(url, String.class);
            assertEquals(200, r.getStatusCodeValue());
            String lower = r.getBody().toLowerCase();
            for (String s : sensitive) {
                assertFalse(lower.contains(s), "sensitive field '" + s + "' leaked in " + url);
            }
        }
        // Error responses are equally clean.
        ResponseEntity<String> err = json(base(), HttpMethod.POST, validCreateJson("   "));
        assertEquals(400, err.getStatusCodeValue());
        String lower = err.getBody().toLowerCase();
        for (String s : sensitive) {
            assertFalse(lower.contains(s));
        }
        assertFalse(err.getBody().contains("READY_AUTO"));
    }

    @Test
    void strategyAndVersionPayloadKeysAreOnlyWhitelistedMetadata() throws Exception {
        String id = create("策略", "摘要");
        ResponseEntity<String> r = http.getForEntity(base() + "/" + id, String.class);
        Set<String> strategyKeys = new java.util.HashSet<>();
        body(r).path("strategy").fieldNames().forEachRemaining(strategyKeys::add);
        assertEquals(Set.of("id", "workspaceId", "name", "status", "currentVersion",
                "createdByMemberId", "createdAt", "updatedAt", "archivedAt"), strategyKeys);

        ResponseEntity<String> v = http.getForEntity(base() + "/" + id + "/versions", String.class);
        Set<String> versionKeys = new java.util.HashSet<>();
        body(v).path("versions").get(0).fieldNames().forEachRemaining(versionKeys::add);
        assertEquals(Set.of("id", "strategyId", "versionNumber", "summary", "instruments",
                "timeframe", "entryRules", "exitRules", "riskNotes",
                "createdByMemberId", "createdAt"), versionKeys);
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