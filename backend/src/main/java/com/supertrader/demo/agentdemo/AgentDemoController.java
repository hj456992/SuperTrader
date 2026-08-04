package com.supertrader.demo.agentdemo;

import com.supertrader.demo.agentdemo.AgentDemoDtos.AcceptedTurnResponse;
import com.supertrader.demo.agentdemo.AgentDemoDtos.CreateSessionRequest;
import com.supertrader.demo.agentdemo.AgentDemoDtos.CreateTurnRequest;
import com.supertrader.demo.agentdemo.AgentDemoDtos.DemoDraftView;
import com.supertrader.demo.agentdemo.AgentDemoDtos.DemoEventEnvelope;
import com.supertrader.demo.agentdemo.AgentDemoDtos.DemoSessionDetail;
import com.supertrader.demo.agentdemo.AgentDemoDtos.DemoSessionSummary;
import com.supertrader.demo.agentdemo.AgentDemoDtos.DraftPatchRequest;
import com.supertrader.demo.agentdemo.AgentDemoDtos.SeedDecisionRequest;
import com.supertrader.demo.agentdemo.AgentDemoDtos.StopResponse;
import com.supertrader.demo.taskcenter.ConversationSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * The Agent Demo REST + SSE controller (Task 8). Routes under
 * {@code /api/v1/agent-demo}. The client may NEVER submit workspace / role /
 * model / base URL / prompt / budget / capability overrides or any secret —
 * the strict DTOs reject unknown fields and the service derives every
 * server-owned concern from server state.
 *
 * <p>No route here introduces any CTP / Gateway / order / cancel type. The
 * Demo does not connect SimNow and produces no trading behaviour.
 */
@RestController
@RequestMapping("/api/v1/agent-demo")
public class AgentDemoController {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoController.class);

    private final AgentDemoService service;

    public AgentDemoController(AgentDemoService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ //
    // Sessions
    // ------------------------------------------------------------------ //

    @PostMapping("/sessions")
    public ResponseEntity<Void> createSession(@RequestBody(required = false) CreateSessionRequest req) {
        ConversationSession s = service.createSession(req == null ? null : req.title());
        return ResponseEntity.created(
                java.net.URI.create("/api/v1/agent-demo/sessions/" + s.id())).build();
    }

    @GetMapping("/sessions")
    public List<DemoSessionSummary> listSessions() {
        return service.listSessions();
    }

    @GetMapping("/sessions/{sessionId}")
    public DemoSessionDetail sessionDetail(@PathVariable String sessionId) {
        return service.sessionDetail(sessionId);
    }

    // ------------------------------------------------------------------ //
    // Turns
    // ------------------------------------------------------------------ //

    @PostMapping("/sessions/{sessionId}/turns")
    public ResponseEntity<AcceptedTurnResponse> postTurn(@PathVariable String sessionId,
                                         @RequestHeader("Idempotency-Key") String idemKey,
                                         @RequestBody CreateTurnRequest req) {
        return ResponseEntity.accepted().body(service.acceptTurn(sessionId, req.content(), idemKey));
    }

    // ------------------------------------------------------------------ //
    // SSE event stream
    // ------------------------------------------------------------------ //

    @GetMapping(value = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String sessionId,
                             @RequestParam(value = "after", required = false, defaultValue = "0") long after,
                             @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
                             HttpServletResponse response) {
        // Disable proxy buffering for SSE.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = new SseEmitter(0L);
        AgentDemoEventHub.SseEmitterSink sink = new AgentDemoEventHub.SseEmitterSink(emitter);
        emitter.onCompletion(() -> log.debug("sse completed for {}", sessionId));
        emitter.onError(t -> log.debug("sse error for {}: {}", sessionId, t.toString()));
        return service.eventHub().subscribe(sessionId, after, lastEventId, sink);
    }

    // ------------------------------------------------------------------ //
    // Stop / Retry
    // ------------------------------------------------------------------ //

    @PostMapping("/runs/{runId}/stop")
    public StopResponse stop(@PathVariable String runId,
                             @RequestHeader("Idempotency-Key") String idemKey) {
        return service.stop(runId, idemKey);
    }

    @PostMapping("/turns/{turnId}/retry")
    public ResponseEntity<AcceptedTurnResponse> retry(@PathVariable String turnId,
                                                      @RequestHeader("Idempotency-Key") String idemKey) {
        AgentDemoRunCoordinator.RetryOutcome out = service.retry(turnId, idemKey);
        if (out.rejected()) {
            throw new AgentDemoApiException(out.errorCode() == null ? "INVALID_STATE"
                    : out.errorCode(), "retry rejected: " + out.errorCode());
        }
        return ResponseEntity.accepted().body(new AcceptedTurnResponse(
                null, turnId, out.newRunId(), "QUEUED", 0));
    }

    // ------------------------------------------------------------------ //
    // Seed decision → Draft
    // ------------------------------------------------------------------ //

    @PostMapping("/seeds/{seedId}/decision")
    public SeedDecisionResponse decideSeed(@PathVariable String seedId,
                                           @RequestBody SeedDecisionRequest req) {
        AgentDemoStore.DraftOutcome out = service.decideSeed(seedId, req.decision());
        DemoDraftView view = service.toView(out.draft());
        return new SeedDecisionResponse(seedId, req.decision(), view);
    }

    // ------------------------------------------------------------------ //
    // Draft patch (If-Match) + validate
    // ------------------------------------------------------------------ //

    @PatchMapping("/drafts/{draftId}")
    public DemoDraftView patchDraft(@PathVariable String draftId,
                                    @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                    @RequestBody DraftPatchRequest req) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new AgentDemoApiException("IF_MATCH_REQUIRED",
                    "If-Match header is required for draft patches");
        }
        int version = parseIfMatch(ifMatch);
        return service.patchDraft(draftId, version, req.field(), req.value(),
                req.evidenceExcerpt());
    }

    @PostMapping("/drafts/{draftId}/validate")
    public Object validateDraft(@PathVariable String draftId) {
        return service.validateDraft(draftId);
    }

    // ------------------------------------------------------------------ //
    // helpers
    // ------------------------------------------------------------------ //

    private static int parseIfMatch(String ifMatch) {
        try {
            return Integer.parseInt(ifMatch.trim().replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new AgentDemoApiException("INVALID_REQUEST",
                    "If-Match must be an integer version");
        }
    }

    /** The response of POST /seeds/{id}/decision. */
    public record SeedDecisionResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("seedId") String seedId,
            @com.fasterxml.jackson.annotation.JsonProperty("decision") String decision,
            @com.fasterxml.jackson.annotation.JsonProperty("draft") DemoDraftView draft) {}
}
