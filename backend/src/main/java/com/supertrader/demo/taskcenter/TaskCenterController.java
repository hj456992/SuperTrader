package com.supertrader.demo.taskcenter;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module 9 task-center API — sessions, turns, strategy co-creation Drafts,
 * human approvals, deterministic validation, local backtest tasks and the
 * append-only audit trail.
 *
 * <p>Contract (ALL inputs are validated server-side; the workspace, the
 * current actor, every object id and every timestamp are ALWAYS derived from
 * server state, never from the request body; NO credential, account or trading
 * field is ever accepted or returned; there is NO DELETE route — cancellation
 * is a state transition with a full audit trail; every write appends an
 * AuditEvent; GET / page loads never produce a run or an audit event):
 * <pre>
 *   GET  /api/v1/agent/sessions                          sessions of the current workspace
 *   POST /api/v1/agent/sessions                          create a session (201)
 *   GET  /api/v1/agent/sessions/{id}                     session + turns + drafts + tasks
 *   POST /api/v1/agent/sessions/{id}/turns               process a user turn (201)
 *   POST /api/v1/agent/sessions/{id}/turns/{turnId}/seed-decision
 *                                                       CO_CREATE / DISCUSS the detected seed
 *   GET  /api/v1/strategy-drafts/{id}                    draft + report + snapshot + approvals
 *   PATCH /api/v1/strategy-drafts/{id}                   patch draft fields
 *   POST /api/v1/strategy-drafts/{id}/confirm-event-evidence
 *                                                       OWNER/ADMIN confirm EVENT facts
 *   POST /api/v1/strategy-drafts/{id}/validate           deterministic validation
 *   POST /api/v1/strategy-drafts/{id}/submit-approval    request human approval
 *   POST /api/v1/strategy-drafts/{id}/approve            OWNER/ADMIN approve + freeze (transaction)
 *   POST /api/v1/strategy-drafts/{id}/reject             OWNER/ADMIN reject
 *   GET  /api/v1/tasks                                   tasks (optional ?status= filter)
 *   GET  /api/v1/tasks/{id}                              task + approvals + attempts
 *   POST /api/v1/tasks/backtests                         create a BACKTEST task (201)
 *   POST /api/v1/tasks/{id}/approve                      OWNER/ADMIN approve
 *   POST /api/v1/tasks/{id}/reject                       OWNER/ADMIN reject
 *   POST /api/v1/tasks/{id}/start                        MANUAL start of the approved task
 *   POST /api/v1/tasks/{id}/cancel                       local cancellation (not trading)
 *   POST /api/v1/tasks/{id}/retry                        new attempt (never overwrites)
 *   GET  /api/v1/agent-runs/{id}                         run + steps + checkpoint
 *   GET  /api/v1/backtest-runs/{id}                      one deterministic backtest run
 *   GET  /api/v1/tasks/{id}/audit                        approvals + audit events
 * </pre>
 *
 * <p>RBAC (server-enforced in the store): reading is allowed for every role;
 * creating sessions / editing drafts / validating / submitting approval /
 * creating-starting-cancelling-retrying tasks require OWNER / ADMIN / TRADER;
 * approving and rejecting require OWNER / ADMIN only (TRADER never approves);
 * a self-approval is recorded with {@code selfApproval=true}. Cross-workspace
 * objects are deliberately 404; illegal state transitions are 409.
 */
@RestController
@RequestMapping("/api/v1")
public class TaskCenterController {

    private final TaskCenterService service;

    public TaskCenterController(TaskCenterService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ //
    // Sessions & turns
    // ------------------------------------------------------------------ //

    @GetMapping("/agent/sessions")
    public TaskCenterDtos.SessionsResponse sessions() {
        return new TaskCenterDtos.SessionsResponse("agent.sessions.v1",
                service.sessions().isEmpty() ? "" : service.storeWorkspaceId(),
                service.sessions());
    }

    @PostMapping("/agent/sessions")
    public ResponseEntity<TaskCenterDtos.SessionResponse> createSession(
            @RequestBody(required = false) TaskCenterDtos.CreateSessionRequest body) {
        ConversationSession created = service.createSession(body == null ? null : body.title());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TaskCenterDtos.SessionResponse("agent.session.v1", created));
    }

    @GetMapping("/agent/sessions/{id}")
    public TaskCenterDtos.SessionDetailResponse sessionDetail(@PathVariable("id") String id) {
        TaskCenterStore.SessionDetail d = service.sessionDetail(id);
        return new TaskCenterDtos.SessionDetailResponse("agent.session-detail.v1",
                d.session(), d.turns(), d.drafts(), d.tasks());
    }

    @PostMapping("/agent/sessions/{id}/turns")
    public ResponseEntity<TaskCenterDtos.TurnResultResponse> createTurn(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.CreateTurnRequest body) {
        TaskCenterService.TurnOutcome outcome =
                service.postTurn(id, body == null ? null : body.content());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TaskCenterDtos.TurnResultResponse("agent.turn-result.v1",
                        outcome.userTurn(), outcome.assistantTurn(), outcome.run(),
                        outcome.steps(), outcome.checkpoint(), outcome.modelUnavailable()));
    }

    @PostMapping("/agent/sessions/{id}/turns/{turnId}/seed-decision")
    public TaskCenterDtos.SeedDecisionResponse seedDecision(
            @PathVariable("id") String id,
            @PathVariable("turnId") String turnId,
            @RequestBody(required = false) TaskCenterDtos.SeedDecisionRequest body) {
        TaskCenterService.SeedDecisionOutcome outcome =
                service.decideSeed(id, turnId, body == null ? null : body.decision());
        return new TaskCenterDtos.SeedDecisionResponse("agent.seed-decision.v1",
                outcome.seed(), outcome.draft());
    }

    // ------------------------------------------------------------------ //
    // Drafts & validation & approval
    // ------------------------------------------------------------------ //

    @GetMapping("/strategy-drafts/{id}")
    public TaskCenterDtos.DraftResponse draft(@PathVariable("id") String id) {
        TaskCenterStore.DraftDetail d = service.draft(id);
        return new TaskCenterDtos.DraftResponse("strategy-drafts.draft.v1",
                d.draft(), d.latestReport(), d.snapshot(), d.approvals());
    }

    @PatchMapping("/strategy-drafts/{id}")
    public TaskCenterDtos.DraftResponse updateDraft(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.DraftPatch body) {
        StrategySpecDraft updated = service.updateDraft(id, body);
        TaskCenterStore.DraftDetail d = service.draft(updated.id());
        return new TaskCenterDtos.DraftResponse("strategy-drafts.draft.v1",
                d.draft(), d.latestReport(), d.snapshot(), d.approvals());
    }

    @PostMapping("/strategy-drafts/{id}/confirm-event-evidence")
    public TaskCenterDtos.DraftResponse confirmEventEvidence(
            @PathVariable("id") String id) {
        StrategySpecDraft updated = service.confirmEventEvidence(id);
        TaskCenterStore.DraftDetail d = service.draft(updated.id());
        return new TaskCenterDtos.DraftResponse("strategy-drafts.draft.v1",
                d.draft(), d.latestReport(), d.snapshot(), d.approvals());
    }

    @PostMapping("/strategy-drafts/{id}/validate")
    public TaskCenterDtos.ValidationResponse validate(@PathVariable("id") String id) {
        TaskCenterStore.ValidationResponse r = service.validateDraft(id);
        return new TaskCenterDtos.ValidationResponse("strategy-drafts.validation.v1",
                r.draft(), r.report());
    }

    @PostMapping("/strategy-drafts/{id}/submit-approval")
    public TaskCenterDtos.DraftResponse submitApproval(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.ReasonRequest body) {
        StrategySpecDraft updated = service.submitApproval(id,
                body == null ? null : body.reason());
        TaskCenterStore.DraftDetail d = service.draft(updated.id());
        return new TaskCenterDtos.DraftResponse("strategy-drafts.draft.v1",
                d.draft(), d.latestReport(), d.snapshot(), d.approvals());
    }

    @PostMapping("/strategy-drafts/{id}/approve")
    public TaskCenterDtos.DraftResponse approve(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.ReasonRequest body) {
        StrategySpecDraft updated = service.approveDraft(id,
                body == null ? null : body.reason());
        TaskCenterStore.DraftDetail d = service.draft(updated.id());
        return new TaskCenterDtos.DraftResponse("strategy-drafts.draft.v1",
                d.draft(), d.latestReport(), d.snapshot(), d.approvals());
    }

    @PostMapping("/strategy-drafts/{id}/reject")
    public TaskCenterDtos.DraftResponse reject(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.ReasonRequest body) {
        StrategySpecDraft updated = service.rejectDraft(id,
                body == null ? null : body.reason());
        TaskCenterStore.DraftDetail d = service.draft(updated.id());
        return new TaskCenterDtos.DraftResponse("strategy-drafts.draft.v1",
                d.draft(), d.latestReport(), d.snapshot(), d.approvals());
    }

    // ------------------------------------------------------------------ //
    // Tasks
    // ------------------------------------------------------------------ //

    @GetMapping("/tasks")
    public TaskCenterDtos.TasksResponse tasks(
            @RequestParam(name = "status", required = false) String status) {
        return new TaskCenterDtos.TasksResponse("tasks.list.v1",
                service.storeWorkspaceId(), service.tasks(status));
    }

    @GetMapping("/tasks/{id}")
    public TaskCenterDtos.TaskResponse task(@PathVariable("id") String id) {
        TaskCenterStore.TaskDetail d = service.task(id);
        return new TaskCenterDtos.TaskResponse("tasks.task.v1",
                d.task(), d.approvals(), d.runs());
    }

    @PostMapping("/tasks/backtests")
    public ResponseEntity<TaskCenterDtos.TaskResponse> createBacktestTask(
            @RequestBody(required = false) TaskCenterDtos.CreateTaskRequest body) {
        if (body == null || body.draftId() == null || body.draftId().isBlank()) {
            throw TaskCenterApiException.invalidBody("创建回测任务必须提供 draftId");
        }
        AgentTask created = service.createBacktestTask(body.draftId(), body.datasetId());
        TaskCenterStore.TaskDetail d = service.task(created.id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TaskCenterDtos.TaskResponse("tasks.task.v1",
                        d.task(), d.approvals(), d.runs()));
    }

    @PostMapping("/tasks/{id}/approve")
    public TaskCenterDtos.TaskResponse approveTask(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.ReasonRequest body) {
        AgentTask updated = service.approveTask(id, body == null ? null : body.reason());
        TaskCenterStore.TaskDetail d = service.task(updated.id());
        return new TaskCenterDtos.TaskResponse("tasks.task.v1",
                d.task(), d.approvals(), d.runs());
    }

    @PostMapping("/tasks/{id}/reject")
    public TaskCenterDtos.TaskResponse rejectTask(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.ReasonRequest body) {
        AgentTask updated = service.rejectTask(id, body == null ? null : body.reason());
        TaskCenterStore.TaskDetail d = service.task(updated.id());
        return new TaskCenterDtos.TaskResponse("tasks.task.v1",
                d.task(), d.approvals(), d.runs());
    }

    @PostMapping("/tasks/{id}/start")
    public TaskCenterDtos.TaskStartResponse startTask(@PathVariable("id") String id) {
        TaskCenterStore.TaskStartResult r = service.startTask(id);
        return new TaskCenterDtos.TaskStartResponse("tasks.task.v1",
                r.task(), r.run());
    }

    @PostMapping("/tasks/{id}/cancel")
    public TaskCenterDtos.TaskResponse cancelTask(
            @PathVariable("id") String id,
            @RequestBody(required = false) TaskCenterDtos.CancelRequest body) {
        AgentTask updated = service.cancelTask(id, body == null ? null : body.reason());
        TaskCenterStore.TaskDetail d = service.task(updated.id());
        return new TaskCenterDtos.TaskResponse("tasks.task.v1",
                d.task(), d.approvals(), d.runs());
    }

    @PostMapping("/tasks/{id}/retry")
    public TaskCenterDtos.TaskResponse retryTask(@PathVariable("id") String id) {
        AgentTask updated = service.retryTask(id);
        TaskCenterStore.TaskDetail d = service.task(updated.id());
        return new TaskCenterDtos.TaskResponse("tasks.task.v1",
                d.task(), d.approvals(), d.runs());
    }

    // ------------------------------------------------------------------ //
    // Runs & audit
    // ------------------------------------------------------------------ //

    @GetMapping("/agent-runs/{id}")
    public TaskCenterDtos.AgentRunResponse agentRun(@PathVariable("id") String id) {
        TaskCenterStore.AgentRunDetail d = service.agentRun(id);
        return new TaskCenterDtos.AgentRunResponse("agent-runs.run.v1",
                d.run(), d.steps(), d.checkpoint());
    }

    @GetMapping("/backtest-runs/{id}")
    public TaskCenterDtos.BacktestRunResponse backtestRun(@PathVariable("id") String id) {
        return new TaskCenterDtos.BacktestRunResponse("backtest-runs.run.v1",
                service.backtestRun(id));
    }

    @GetMapping("/tasks/{id}/audit")
    public TaskCenterDtos.TaskAuditResponse taskAudit(@PathVariable("id") String id) {
        TaskCenterStore.TaskAudit a = service.taskAudit(id);
        return new TaskCenterDtos.TaskAuditResponse("tasks.audit.v1",
                id, a.approvals(), a.auditEvents());
    }
}
