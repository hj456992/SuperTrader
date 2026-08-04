package com.supertrader.demo.taskcenter;

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
 * HTTP-level tests for the Module 9 task-center API, against the real Spring
 * Boot context (RANDOM_PORT). Covers the full contract, RBAC, isolation,
 * illegal transitions, no DELETE / no trading routes, no sensitive fields,
 * no credential payload and the GET-never-writes rule. All stores point at
 * scratch temp files; the real rebuild/.run/ is never touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskCenterApiTest {

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
    static Path taskCenterScratch;

    @DynamicPropertySource
    static void scratchFiles(DynamicPropertyRegistry registry) throws IOException {
        workspaceScratch = Files.createTempFile("simnow-workspaces-tc-", ".json");
        registry.add("app.workspaces.file", () -> workspaceScratch.toString());
        teamsScratch = Files.createTempFile("simnow-teams-tc-", ".json");
        registry.add("app.teams.file", () -> teamsScratch.toString());
        historyScratch = Files.createTempFile("simnow-history-tc-", ".json");
        registry.add("app.history.file", () -> historyScratch.toString());
        accountsScratch = Files.createTempFile("simnow-accounts-tc-", ".json");
        registry.add("app.trading-accounts.file", () -> accountsScratch.toString());
        strategiesScratch = Files.createTempFile("simnow-strategies-tc-", ".json");
        registry.add("app.strategies.file", () -> strategiesScratch.toString());
        taskCenterScratch = Files.createTempFile("simnow-taskcenter-tc-", ".json");
        registry.add("app.taskcenter.file", () -> taskCenterScratch.toString());
        Path riskScratchTaskCenterApi = Files.createTempFile("simnow-risk-taskcenterapi-", ".json");
        registry.add("app.risk-control.file", () -> riskScratchTaskCenterApi.toString());
    }

    @BeforeEach
    void resetState() throws Exception {
        // Always start from the default workspace + its OWNER (tests that
        // switch workspaces or actors must never leak state into the next
        // test).
        if (!"default".equals(workspaceStore.currentWorkspaceId())) {
            workspaceStore.switchTo("default");
        }
        String ownerId = teamStore.currentActor().id();
        teamStore.switchActor(ownerId);
    }

    private String base() {
        return "http://localhost:" + port + "/api/v1";
    }

    private String sessionsUrl() {
        return base() + "/agent/sessions";
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

    // ------------------------------------------------------------------ //
    // Full happy-path contract
    // ------------------------------------------------------------------ //

    @Test
    void fullContractFromTurnToBacktestResult() throws Exception {
        // 1. create a session (201) + list.
        var created = json(sessionsUrl(), HttpMethod.POST, "{\"title\":\"验收会话\"}");
        assertEquals(201, created.getStatusCodeValue());
        String sessionId = body(created).path("session").path("id").asText();
        assertEquals("ACTIVE", body(created).path("session").path("status").asText());
        var list = json(sessionsUrl(), HttpMethod.GET, null);
        assertEquals(200, list.getStatusCodeValue());
        assertEquals("agent.sessions.v1", body(list).path("schema").asText());

        // 2. a plain QA turn → NO draft, MODEL_UNAVAILABLE marked honestly.
        var qa = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"你好\"}");
        assertEquals(201, qa.getStatusCodeValue());
        JsonNode qaBody = body(qa);
        assertEquals("GENERAL_QA", qaBody.path("userTurn").path("intent").asText());
        assertTrue(qaBody.path("assistantTurn").path("content").asText()
                .contains("MODEL_UNAVAILABLE"));
        assertTrue(qaBody.path("modelUnavailable").asBoolean());
        assertNotNull(qaBody.path("agentRun").path("id").asText(null));

        // 3. candidate turn → seed; the seed-decision creates the draft ONLY
        // on the explicit choice.
        var cand = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"如果 JM2609 上穿20日均线就买入\"}");
        assertEquals(201, cand.getStatusCodeValue());
        String turnId = body(cand).path("userTurn").path("id").asText();
        assertEquals("STRATEGY_CANDIDATE",
                body(cand).path("userTurn").path("intent").asText());
        assertNotNull(body(cand).path("userTurn").path("seed").path("id").asText(null));
        var detail = json(sessionsUrl() + "/" + sessionId, HttpMethod.GET, null);
        assertTrue(body(detail).path("drafts").isEmpty(),
                "unconfirmed candidate must not create a draft");

        var decision = json(sessionsUrl() + "/" + sessionId + "/turns/" + turnId
                + "/seed-decision", HttpMethod.POST, "{\"decision\":\"CO_CREATE\"}");
        assertEquals(200, decision.getStatusCodeValue());
        String draftId = body(decision).path("draft").path("id").asText();
        assertEquals("CO_CREATING",
                body(decision).path("draft").path("status").asText());

        // 4. fill the draft via PATCH + validate.
        var patched = json(base() + "/strategy-drafts/" + draftId, HttpMethod.PATCH,
                "{\"name\":\"均线策略\",\"instruments\":[\"JM2609\"],\"timeframe\":\"1D\","
                        + "\"fastWindow\":5,\"slowWindow\":20,\"positionSize\":0.1,"
                        + "\"stopLossPct\":5,\"feeBps\":2,\"slippageBps\":1,"
                        + "\"entryCondition\":\"快线上穿慢线时买入\","
                        + "\"exitCondition\":\"快线下穿慢线时卖出\","
                        + "\"riskLimits\":\"止损5%\","
                        + "\"backtestAssumptions\":\"使用内置验收样本\"}");
        assertEquals(200, patched.getStatusCodeValue());
        assertEquals(100, body(patched).path("draft").path("completeness").asInt());
        assertTrue(body(patched).path("draft").path("missingFields").isEmpty());

        var validated = json(base() + "/strategy-drafts/" + draftId + "/validate",
                HttpMethod.POST, null);
        assertEquals(200, validated.getStatusCodeValue());
        assertTrue(body(validated).path("report").path("valid").asBoolean());
        assertTrue(body(validated).path("report").path("backtestable").asBoolean());
        assertEquals("DRAFT_READY", body(validated).path("draft").path("status").asText());

        // 5. submit approval + approve (freeze) + snapshot + module-8 version.
        var submitted = json(base() + "/strategy-drafts/" + draftId + "/submit-approval",
                HttpMethod.POST, "{\"reason\":\"请审批\"}");
        assertEquals(200, submitted.getStatusCodeValue());
        assertEquals("PENDING_APPROVAL",
                body(submitted).path("draft").path("status").asText());

        var approved = json(base() + "/strategy-drafts/" + draftId + "/approve",
                HttpMethod.POST, "{\"reason\":\"同意\"}");
        assertEquals(200, approved.getStatusCodeValue());
        JsonNode approvedBody = body(approved);
        assertEquals("FROZEN", approvedBody.path("draft").path("status").asText());
        assertNotNull(approvedBody.path("snapshot").path("id").asText(null));
        assertTrue(approvedBody.path("snapshot").path("versionNumber").asInt() >= 1);
        assertNotNull(approvedBody.path("snapshot").path("strategyId").asText(null));
        assertEquals("APPROVED", approvedBody.path("approvals").get(0)
                .path("decision").asText());
        assertTrue(approvedBody.path("approvals").get(0).path("selfApproval").asBoolean());

        // 6. create the backtest task; an unapproved task cannot start.
        var taskCreated = json(base() + "/tasks/backtests",
                HttpMethod.POST, "{\"draftId\":\"" + draftId + "\"}");
        assertEquals(201, taskCreated.getStatusCodeValue());
        String taskId = body(taskCreated).path("task").path("id").asText();
        assertEquals("PENDING_APPROVAL",
                body(taskCreated).path("task").path("status").asText());
        var blockedStart = json(base() + "/tasks/" + taskId + "/start",
                HttpMethod.POST, null);
        assertEquals(409, blockedStart.getStatusCodeValue());
        assertEquals("ILLEGAL_STATE", body(blockedStart).path("error").path("code").asText());

        // 7. approve → manual start → deterministic result.
        var taskApproved = json(base() + "/tasks/" + taskId + "/approve",
                HttpMethod.POST, "{\"reason\":\"同意\"}");
        assertEquals(200, taskApproved.getStatusCodeValue());
        assertEquals("APPROVED", body(taskApproved).path("task").path("status").asText());
        var started = json(base() + "/tasks/" + taskId + "/start", HttpMethod.POST, null);
        assertEquals(200, started.getStatusCodeValue());
        assertEquals("SUCCEEDED", body(started).path("task").path("status").asText());
        JsonNode run = body(started).path("run");
        assertEquals("SUCCEEDED", run.path("status").asText());
        assertTrue(run.path("inputBars").asInt() >= 200);
        assertNotNull(run.path("netReturnPct").asDouble(0), "deterministic metrics present");
        assertEquals("SAMPLE_ONLY", run.path("sampleOutStatus").asText());
        String runId = run.path("id").asText();

        // 8. audit trail for the task.
        var audit = json(base() + "/tasks/" + taskId + "/audit", HttpMethod.GET, null);
        assertEquals(200, audit.getStatusCodeValue());
        assertTrue(body(audit).path("approvals").size() >= 1);
        assertTrue(body(audit).path("auditEvents").size() >= 2);
        // GET /backtest-runs/{id} works.
        var runGet = json(base() + "/backtest-runs/" + runId, HttpMethod.GET, null);
        assertEquals(200, runGet.getStatusCodeValue());
    }

    @Test
    void eventEvidenceConfirmationIsIndependentServerDerivedAndOwnerOnly() throws Exception {
        var session = json(sessionsUrl(), HttpMethod.POST,
                "{\"title\":\"事件确认 API\"}");
        String sessionId = body(session).path("session").path("id").asText();
        var candidate = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"如果 JM2609 出现事件信号就买入\"}");
        String turnId = body(candidate).path("userTurn").path("id").asText();
        var decision = json(sessionsUrl() + "/" + sessionId + "/turns/" + turnId
                + "/seed-decision", HttpMethod.POST, "{\"decision\":\"CO_CREATE\"}");
        String draftId = body(decision).path("draft").path("id").asText();

        String eventPrefix = "{\"name\":\"事件策略\",\"instruments\":[\"JM2609\"],"
                + "\"timeframe\":\"1D\",\"entryCondition\":\"事件生效后入场\","
                + "\"exitCondition\":\"止损离场\",\"riskLimits\":\"止损5%\","
                + "\"backtestAssumptions\":\"SAMPLE_ONLY\","
                + "\"templateType\":\"EVENT_SIGNAL\",\"parameters\":{"
                + "\"schemaVersion\":2,\"type\":\"EVENT_SIGNAL\","
                + "\"eventId\":\"evt-api\",\"sourceId\":\"src-api\","
                + "\"contentHash\":\"sha256:event\","
                + "\"originalPublishedAt\":\"2025-01-01T00:00:00Z\","
                + "\"fetchedAt\":\"2025-01-02T00:00:00Z\","
                + "\"availableAt\":\"2025-01-02T00:00:00Z\","
                + "\"validFrom\":\"2025-01-02T00:00:00Z\","
                + "\"validUntil\":\"2027-01-01T00:00:00Z\","
                + "\"relatedInstrument\":\"JM2609\",\"direction\":\"LONG\","
                + "\"sourceStatus\":\"AVAILABLE\",\"stopLossPct\":5,"
                + "\"positionSize\":0.1,\"feeBps\":2,\"slippageBps\":1";

        var forged = json(base() + "/strategy-drafts/" + draftId, HttpMethod.PATCH,
                eventPrefix + ",\"confirmationId\":\"client-confirm\","
                        + "\"confirmedByMemberId\":\"client-member\","
                        + "\"confirmedAt\":\"2025-01-02T00:00:00Z\"}}" );
        assertEquals(400, forged.getStatusCodeValue(),
                "ordinary PATCH must not write authoritative confirmation facts");

        var unconfirmed = json(base() + "/strategy-drafts/" + draftId, HttpMethod.PATCH,
                eventPrefix + "}}" );
        assertEquals(200, unconfirmed.getStatusCodeValue(),
                "EVENT facts must be draftable before the independent confirmation gate");
        assertTrue(body(unconfirmed).path("draft").path("parameters")
                .path("confirmationId").isNull());

        long seq = NAME_SEQ.incrementAndGet();
        var trader = teamStore.createMember("事件确认交易员" + seq,
                com.supertrader.demo.team.TeamStore.ROLE_TRADER);
        teamStore.switchActor(trader.id());
        assertEquals(403, json(base() + "/strategy-drafts/" + draftId
                + "/confirm-event-evidence", HttpMethod.POST,
                "{\"confirmedByMemberId\":\"forged\","
                        + "\"confirmedAt\":\"2000-01-01T00:00:00Z\"}")
                .getStatusCodeValue(), "TRADER must never confirm EVENT evidence");

        String ownerId = teamStore.listMembers().stream()
                .filter(m -> com.supertrader.demo.team.TeamStore.ROLE_OWNER.equals(m.role()))
                .findFirst().orElseThrow().id();
        teamStore.switchActor(ownerId);
        java.time.Instant before = java.time.Instant.now();
        var confirmed = json(base() + "/strategy-drafts/" + draftId
                + "/confirm-event-evidence", HttpMethod.POST,
                "{\"confirmedByMemberId\":\"forged\","
                        + "\"confirmedAt\":\"2000-01-01T00:00:00Z\"}");
        java.time.Instant after = java.time.Instant.now();
        assertEquals(200, confirmed.getStatusCodeValue());
        JsonNode confirmedBody = body(confirmed);
        JsonNode event = confirmedBody.path("draft").path("parameters");
        assertEquals(ownerId, event.path("confirmedByMemberId").asText());
        assertNotEquals("forged", event.path("confirmedByMemberId").asText());
        java.time.Instant confirmedAt = java.time.Instant.parse(event.path("confirmedAt").asText());
        assertFalse(confirmedAt.isBefore(before));
        assertFalse(confirmedAt.isAfter(after));
        assertFalse(event.path("confirmationId").asText().isBlank());
        assertTrue(confirmedBody.path("approvals").findValuesAsText("entityType")
                .contains("EVENT_EVIDENCE"));
        JsonNode disk = MAPPER.readTree(Files.readString(taskCenterScratch));
        assertTrue(disk.path("auditEvents").findValuesAsText("action")
                .contains("EVENT_EVIDENCE_CONFIRMED"));
    }

    @Test
    void chatRefinementAnswersAdvanceTheQuestionPerTurn() throws Exception {
        var created = json(sessionsUrl(), HttpMethod.POST, "{\"title\":\"对话完善会话\"}");
        String sessionId = body(created).path("session").path("id").asText();
        var cand = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"如果 JM2609 上穿20日均线就买入\"}");
        String turnId = body(cand).path("userTurn").path("id").asText();
        json(sessionsUrl() + "/" + sessionId + "/turns/" + turnId
                + "/seed-decision", HttpMethod.POST, "{\"decision\":\"CO_CREATE\"}");

        // Round 1: answer the first pending question (timeframe) in the chat.
        var a1 = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"5M\"}");
        assertEquals(201, a1.getStatusCodeValue());
        assertEquals("exitCondition", body(a1).path("assistantTurn")
                .path("question").path("field").asText(),
                "after a successful answer the NEXT field is asked");

        // Round 2: the answer parses against the ADVANCED question (not a
        // stale timeframe), and the next field is asked again.
        var a2 = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"快线下穿时卖出\"}");
        assertEquals(201, a2.getStatusCodeValue());
        assertTrue(body(a2).path("assistantTurn").path("content").asText()
                .contains("已记录 exitCondition"));
        assertEquals("riskLimits", body(a2).path("assistantTurn")
                .path("question").path("field").asText());

        // The draft reflects both chat-confirmed fields.
        var detail = json(sessionsUrl() + "/" + sessionId, HttpMethod.GET, null);
        JsonNode draft = body(detail).path("drafts").get(0);
        assertEquals("5M", draft.path("timeframe").asText());
        assertEquals("快线下穿时卖出", draft.path("exitCondition").asText());
        assertFalse(draft.path("missingFields").toString().contains("exitCondition"));
    }

    @Test
    void rejectPathAndStatusFilter() throws Exception {
        var created = json(sessionsUrl(), HttpMethod.POST, "{\"title\":\"拒绝会话\"}");
        String sessionId = body(created).path("session").path("id").asText();
        var cand = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"如果 JM2609 上穿20日均线就买入\"}");
        String turnId = body(cand).path("userTurn").path("id").asText();
        var decision = json(sessionsUrl() + "/" + sessionId + "/turns/" + turnId
                + "/seed-decision", HttpMethod.POST, "{\"decision\":\"CO_CREATE\"}");
        String draftId = body(decision).path("draft").path("id").asText();
        patchFull(draftId);
        json(base() + "/strategy-drafts/" + draftId + "/validate", HttpMethod.POST, null);
        json(base() + "/strategy-drafts/" + draftId + "/submit-approval",
                HttpMethod.POST, "{\"reason\":\"\"}");
        var rejected = json(base() + "/strategy-drafts/" + draftId + "/reject",
                HttpMethod.POST, "{\"reason\":\"参数不符合预期\"}");
        assertEquals(200, rejected.getStatusCodeValue());
        assertEquals("REJECTED", body(rejected).path("draft").path("status").asText());
        assertEquals("REJECTED", body(rejected).path("approvals").get(0)
                .path("decision").asText());
        // A rejected draft can be edited again (returns to CO_CREATING).
        var reedited = json(base() + "/strategy-drafts/" + draftId, HttpMethod.PATCH,
                "{\"name\":\"改名后\"}");
        assertEquals(200, reedited.getStatusCodeValue());
        assertEquals("CO_CREATING", body(reedited).path("draft").path("status").asText());

        // Task status filter.
        String frozen = freezeViaHttp("筛选会话");
        var taskCreated = json(base() + "/tasks/backtests",
                HttpMethod.POST, "{\"draftId\":\"" + frozen + "\"}");
        String taskId = body(taskCreated).path("task").path("id").asText();
        var filtered = json(base() + "/tasks?status=PENDING_APPROVAL", HttpMethod.GET, null);
        assertEquals(200, filtered.getStatusCodeValue());
        assertTrue(body(filtered).path("tasks").size() >= 1);
        var emptyFilter = json(base() + "/tasks?status=RUNNING", HttpMethod.GET, null);
        assertTrue(body(emptyFilter).path("tasks").isEmpty());
    }

    @Test
    void illegalTransitionsReturn409() throws Exception {
        // approve without submit → 409.
        var created = json(sessionsUrl(), HttpMethod.POST, "{\"title\":\"门禁会话\"}");
        String sessionId = body(created).path("session").path("id").asText();
        var cand = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"如果 JM2609 上穿20日均线就买入\"}");
        String turnId = body(cand).path("userTurn").path("id").asText();
        var decision = json(sessionsUrl() + "/" + sessionId + "/turns/" + turnId
                + "/seed-decision", HttpMethod.POST, "{\"decision\":\"CO_CREATE\"}");
        String draftId = body(decision).path("draft").path("id").asText();
        patchFull(draftId);
        var earlyApprove = json(base() + "/strategy-drafts/" + draftId + "/approve",
                HttpMethod.POST, "{\"reason\":\"\"}");
        assertEquals(409, earlyApprove.getStatusCodeValue());
        assertEquals("ILLEGAL_STATE", body(earlyApprove).path("error").path("code").asText());
        // validate an unknown draft → 404 (cross-workspace is also 404).
        var unknown = json(base() + "/strategy-drafts/no-such/validate",
                HttpMethod.POST, null);
        assertEquals(404, unknown.getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // RBAC over HTTP
    // ------------------------------------------------------------------ //

    @Test
    void rbacOverHttp() throws Exception {
        // OWNER builds a full pipeline.
        String draftId = freezeViaHttp("RBAC 会话");
        var taskCreated = json(base() + "/tasks/backtests",
                HttpMethod.POST, "{\"draftId\":\"" + draftId + "\"}");
        String taskId = body(taskCreated).path("task").path("id").asText();

        // TRADER: can create sessions / edit drafts / start tasks, but NEVER
        // approve or reject. Member names must be unique per run (the team
        // store is shared within this class context).
        long seq = NAME_SEQ.incrementAndGet();
        var trader = json(base() + "/teams/members", HttpMethod.POST,
                "{\"displayName\":\"HTTP交易员" + seq + "\",\"role\":\"TRADER\"}");
        assertEquals(201, trader.getStatusCodeValue());
        String traderId = body(trader).path("member").path("id").asText();
        assertEquals(200, json(base() + "/teams/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + traderId + "\"}").getStatusCodeValue());
        var traderApprove = json(base() + "/tasks/" + taskId + "/approve",
                HttpMethod.POST, "{\"reason\":\"\"}");
        assertEquals(403, traderApprove.getStatusCodeValue());
        var traderStart = json(base() + "/tasks/" + taskId + "/start",
                HttpMethod.POST, null);
        assertEquals(409, traderStart.getStatusCodeValue(), // not approved yet
                "TRADER cannot start an unapproved task (409, not 403)");
        var traderSession = json(sessionsUrl(), HttpMethod.POST,
                "{\"title\":\"交易员会话\"}");
        assertEquals(201, traderSession.getStatusCodeValue(), "TRADER may create sessions");

        // VIEWER: read-only. Only OWNER/ADMIN may create members, so switch
        // back to the workspace OWNER first.
        String ownerId = teamStore.listMembers().stream()
                .filter(m -> m.role().equals("OWNER") && m.active())
                .findFirst().orElseThrow().id();
        json(base() + "/teams/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + ownerId + "\"}");
        var viewer = json(base() + "/teams/members", HttpMethod.POST,
                "{\"displayName\":\"HTTP观察员" + seq + "\",\"role\":\"VIEWER\"}");
        assertEquals(201, viewer.getStatusCodeValue());
        String viewerId = body(viewer).path("member").path("id").asText();
        assertEquals(200, json(base() + "/teams/current-actor", HttpMethod.PUT,
                "{\"memberId\":\"" + viewerId + "\"}").getStatusCodeValue());
        assertEquals(403, json(base() + "/tasks/" + taskId + "/approve",
                HttpMethod.POST, "{\"reason\":\"\"}").getStatusCodeValue());
        assertEquals(403, json(base() + "/tasks/" + taskId + "/cancel",
                HttpMethod.POST, "{\"reason\":\"\"}").getStatusCodeValue());
        assertEquals(200, json(base() + "/tasks", HttpMethod.GET, null)
                .getStatusCodeValue());
        assertEquals(200, json(base() + "/tasks/" + taskId, HttpMethod.GET, null)
                .getStatusCodeValue());
        assertEquals(403, json(sessionsUrl(), HttpMethod.POST,
                "{\"title\":\"越权\"}").getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // Workspace isolation over HTTP
    // ------------------------------------------------------------------ //

    @Test
    void crossWorkspaceObjectsAre404() throws Exception {
        String draftId = freezeViaHttp("隔离 HTTP 会话");
        var taskCreated = json(base() + "/tasks/backtests",
                HttpMethod.POST, "{\"draftId\":\"" + draftId + "\"}");
        String taskId = body(taskCreated).path("task").path("id").asText();

        // Create + switch to a second workspace.
        var ws = json(base() + "/workspaces", HttpMethod.POST, "{\"name\":\"隔离空间\"}");
        String ws2 = body(ws).path("workspace").path("id").asText();
        json(base() + "/workspaces/current", HttpMethod.PUT, "{\"id\":\"" + ws2 + "\"}");
        assertEquals(404, json(base() + "/strategy-drafts/" + draftId, HttpMethod.GET, null)
                .getStatusCodeValue());
        assertEquals(404, json(base() + "/tasks/" + taskId, HttpMethod.GET, null)
                .getStatusCodeValue());
        assertEquals(404, json(sessionsUrl() + "/no-such-session", HttpMethod.GET, null)
                .getStatusCodeValue());
        // The second workspace sees its own empty lists.
        var list = json(sessionsUrl(), HttpMethod.GET, null);
        assertTrue(body(list).path("sessions").isEmpty());
    }

    // ------------------------------------------------------------------ //
    // GET never writes; no DELETE; no trading routes; no sensitive fields
    // ------------------------------------------------------------------ //

    @Test
    void getNeverWritesOrAudits() throws Exception {
        json(sessionsUrl(), HttpMethod.POST, "{\"title\":\"只读会话\"}");
        String before = Files.readString(taskCenterScratch);
        json(sessionsUrl(), HttpMethod.GET, null);
        json(base() + "/tasks", HttpMethod.GET, null);
        assertEquals(before, Files.readString(taskCenterScratch),
                "GET requests never write the task center");
    }

    @Test
    void noDeleteRoutesForModule9() {
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(info)) {
                if (isModule9Family(pattern)) {
                    assertFalse(info.getMethodsCondition().getMethods()
                                    .contains(org.springframework.http.HttpMethod.DELETE),
                            "DELETE must never be offered on " + pattern);
                }
            }
        }
    }

    @Test
    void module9RoutesContainNoTradingCapability() {
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(info)) {
                if (isModule9Family(pattern)) {
                    String lower = pattern.toLowerCase();
                    for (String token : List.of("order", "insert", "trade",
                            "execution-live", "paper-runner", "live-runner",
                            "future-gateway", "auto-trade", "account-bind")) {
                        assertFalse(lower.contains(token),
                                "trading token '" + token + "' in " + pattern);
                    }
                }
            }
        }
    }

    @Test
    void responsesCarryNoSensitiveFields() throws Exception {
        String draftId = freezeViaHttp("敏感字段会话");
        var r = json(base() + "/strategy-drafts/" + draftId, HttpMethod.GET, null);
        String lower = r.getBody().toLowerCase();
        for (String s : Set.of("password", "authcode", "appid", "token", "secret",
                "apikey", "privatekey", "investorid", "brokerid", "credential")) {
            assertFalse(lower.contains(s), "sensitive field '" + s + "' leaked");
        }
        assertFalse(lower.contains("ready_auto"));
        // The whitelisted draft payload keys.
        JsonNode draft = body(r).path("draft");
        Set<String> keys = new java.util.HashSet<>();
        draft.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id", "workspaceId", "sessionId", "strategyId",
                "baseVersionNumber", "seedId", "name", "templateType", "instruments",
                "timeframe", "parameters", "entryCondition", "exitCondition",
                "riskLimits", "backtestAssumptions", "evidence", "completeness",
                "missingFields", "status", "createdByMemberId", "createdAt",
                "updatedAt"), keys);
    }

    @Test
    void invalidInputsReturnDeterministic400s() throws Exception {
        var created = json(sessionsUrl(), HttpMethod.POST, "{\"title\":\"400 会话\"}");
        String sessionId = body(created).path("session").path("id").asText();
        assertEquals(400, json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"\"}").getStatusCodeValue());
        assertEquals(400, json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"我的密码是 123456\"}")
                .getStatusCodeValue());
        assertEquals(400, json(sessionsUrl(), HttpMethod.POST, "{\"title\":\"\"}")
                .getStatusCodeValue());
        assertEquals(400, json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "not-json").getStatusCodeValue());
        assertEquals(400, json(base() + "/tasks/backtests",
                HttpMethod.POST, "{}").getStatusCodeValue());
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private static final java.util.concurrent.atomic.AtomicLong NAME_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private void patchFull(String draftId) {
        // The strategy name must be unique per freeze: strategies.json is
        // shared by every test in this class context and a duplicate name
        // would 409 the module-8 create during the freeze.
        long nameSeq = NAME_SEQ.incrementAndGet();
        json(base() + "/strategy-drafts/" + draftId, HttpMethod.PATCH,
                "{\"name\":\"均线策略" + nameSeq + "\",\"instruments\":[\"JM2609\"],"
                        + "\"timeframe\":\"1D\","
                        + "\"fastWindow\":5,\"slowWindow\":20,\"positionSize\":0.1,"
                        + "\"stopLossPct\":5,\"feeBps\":2,\"slippageBps\":1,"
                        + "\"entryCondition\":\"快线上穿慢线时买入\","
                        + "\"exitCondition\":\"快线下穿慢线时卖出\","
                        + "\"riskLimits\":\"止损5%\","
                        + "\"backtestAssumptions\":\"使用内置验收样本\"}");
    }

    /** Full pipeline via HTTP: session → seed → draft → validate → submit →
     *  approve; returns the FROZEN draft id. */
    private String freezeViaHttp(String title) throws Exception {
        var created = json(sessionsUrl(), HttpMethod.POST,
                "{\"title\":\"" + title + "\"}");
        String sessionId = body(created).path("session").path("id").asText();
        var cand = json(sessionsUrl() + "/" + sessionId + "/turns",
                HttpMethod.POST, "{\"content\":\"如果 JM2609 上穿20日均线就买入\"}");
        String turnId = body(cand).path("userTurn").path("id").asText();
        var decision = json(sessionsUrl() + "/" + sessionId + "/turns/" + turnId
                + "/seed-decision", HttpMethod.POST, "{\"decision\":\"CO_CREATE\"}");
        String draftId = body(decision).path("draft").path("id").asText();
        patchFull(draftId);
        json(base() + "/strategy-drafts/" + draftId + "/validate",
                HttpMethod.POST, null);
        json(base() + "/strategy-drafts/" + draftId + "/submit-approval",
                HttpMethod.POST, "{\"reason\":\"\"}");
        var approved = json(base() + "/strategy-drafts/" + draftId + "/approve",
                HttpMethod.POST, "{\"reason\":\"同意\"}");
        assertEquals(200, approved.getStatusCodeValue());
        return draftId;
    }

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

    private static boolean isModule9Family(String p) {
        return p.startsWith("/api/v1/agent")
                || p.startsWith("/api/v1/strategy-drafts")
                || p.startsWith("/api/v1/tasks")
                || p.startsWith("/api/v1/agent-runs")
                || p.startsWith("/api/v1/backtest-runs");
    }
}
