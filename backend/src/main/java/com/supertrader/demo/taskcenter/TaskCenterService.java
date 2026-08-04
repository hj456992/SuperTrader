package com.supertrader.demo.taskcenter;

import com.supertrader.demo.team.TeamStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The Module 9 task-center service: the orchestration layer between the HTTP
 * controller and the persistence / harness / validator / runner components.
 *
 * <p>It owns the turn-processing flow: validate the user content → classify +
 * plan inside the Agent Runtime Harness (ToolProxy-gated, budgeted, traced) →
 * persist the user turn + the Agent run/steps/checkpoint + (only when the
 * user's answer parsed) the Draft field patch → persist the assistant turn
 * (at most one question). It never lets the harness create a Draft, freeze a
 * version, approve or start anything by itself.
 */
@Service
public class TaskCenterService {

    private final TaskCenterStore store;
    private final AgentRuntimeHarness harness;
    private final TeamStore teamStore;

    public TaskCenterService(TaskCenterStore store, AgentRuntimeHarness harness,
                             TeamStore teamStore) {
        this.store = store;
        this.harness = harness;
        this.teamStore = teamStore;
    }

    // ------------------------------------------------------------------ //
    // Sessions & turns
    // ------------------------------------------------------------------ //

    public ConversationSession createSession(String title) {
        return store.createSession(title);
    }

    /**
     * Process one user turn end-to-end. NEVER creates a Draft / task by
     * itself: a detected candidate only produces a seed that waits for the
     * user's explicit decision.
     */
    public TurnOutcome postTurn(String sessionId, String rawContent) {
        String content = TaskCenterStore.validateTurnContent(rawContent); // 400 fail-closed
        TaskCenterStore.SessionDetail detail = store.sessionDetail(sessionId); // 404 + isolation
        ConversationSession session = detail.session();

        // The active co-creation draft of this session (CO_CREATING only) and
        // the pending question of the last assistant turn.
        StrategySpecDraft activeDraft = detail.drafts().stream()
                .filter(d -> StrategySpecDraft.STATUS_CO_CREATING.equals(d.status()))
                .findFirst().orElse(null);
        TurnQuestion pendingQuestion = null;
        if (activeDraft != null) {
            // The live pending question: the last assistant turn whose field is
            // NOT yet confirmed on the draft. A stale question (already
            // answered via the edit grid) is skipped, so a chat answer always
            // parses against the draft's current highest-impact missing field.
            for (int i = detail.turns().size() - 1; i >= 0; i--) {
                ConversationTurn t = detail.turns().get(i);
                if (ConversationTurn.ROLE_ASSISTANT.equals(t.role())
                        && t.question() != null
                        && !SpecFieldHelpers.fieldConfirmed(activeDraft, t.question().field())) {
                    pendingQuestion = t.question();
                    break;
                }
            }
            if (pendingQuestion == null) {
                pendingQuestion = SpecFieldHelpers.nextQuestion(activeDraft);
            }
        }
        String actorRole = currentRole();

        AgentRuntimeHarness.TurnExecution execution = harness.executeTurn(
                session, content, activeDraft, pendingQuestion, actorRole);

        ConversationTurn userTurn = store.addUserTurn(sessionId, content,
                execution.intent(), execution.seed());

        // Apply the parsed Draft patch (only when the user answered the single
        // question and the answer parsed) — the evidence links to the turn.
        StrategySpecDraft updatedDraft = null;
        if (execution.patch() != null && execution.parseError() == null && activeDraft != null) {
            updatedDraft = store.updateDraft(activeDraft.id(), execution.patch(),
                    userTurn.id());
        }

        store.persistAgentRun(execution.run(), execution.steps(), execution.checkpoint());

        ConversationTurn assistantTurn = store.addAssistantTurn(sessionId,
                execution.assistantContent(), execution.question(),
                execution.modelUnavailable(), execution.intent());

        return new TurnOutcome(userTurn, assistantTurn, execution.run(),
                execution.steps(), execution.checkpoint(), execution.modelUnavailable(),
                updatedDraft);
    }

    /** The user's explicit seed decision (CO_CREATE / DISCUSS). */
    public SeedDecisionOutcome decideSeed(String sessionId, String turnId, String decision) {
        TaskCenterStore.SeedDecisionResult result = store.decideSeed(sessionId, turnId, decision);
        if (result.draft() != null) {
            // Welcome the user into co-creation and ask the ONE highest-impact
            // question right away.
            TurnQuestion question = SpecFieldHelpers.nextQuestion(result.draft());
            store.addAssistantTurn(sessionId,
                    "已进入策略共创。草案已创建（SMA_CROSS 模板，完整度 "
                            + result.draft().completeness() + "%）。"
                            + (question == null ? "" : "\n" + question.prompt()),
                    question, false, IntentClassifier.STRATEGY_REFINEMENT);
        } else {
            store.addAssistantTurn(sessionId,
                    "好的，该候选仅作为讨论，不会创建任何草案或任务。", null, false,
                    IntentClassifier.STRATEGY_HYPOTHESIS);
        }
        return new SeedDecisionOutcome(result.seed(), result.draft());
    }

    // ------------------------------------------------------------------ //
    // Pass-throughs (RBAC + state machines enforced in the store)
    // ------------------------------------------------------------------ //

    public TaskCenterStore.SessionDetail sessionDetail(String sessionId) {
        return store.sessionDetail(sessionId);
    }

    public List<ConversationSession> sessions() {
        return store.listSessions();
    }

    public TaskCenterStore.DraftDetail draft(String draftId) {
        return store.draft(draftId);
    }

    public StrategySpecDraft updateDraft(String draftId, TaskCenterDtos.DraftPatch patch) {
        return store.updateDraft(draftId, patch, null);
    }

    public StrategySpecDraft confirmEventEvidence(String draftId) {
        return store.confirmEventEvidence(draftId);
    }

    public TaskCenterStore.ValidationResponse validateDraft(String draftId) {
        return store.validateDraft(draftId);
    }

    public StrategySpecDraft submitApproval(String draftId, String reason) {
        return store.submitApproval(draftId, TaskCenterStore.validateReason(reason));
    }

    public StrategySpecDraft approveDraft(String draftId, String reason) {
        return store.approveAndFreeze(draftId, TaskCenterStore.validateReason(reason),
                currentMemberId());
    }

    public StrategySpecDraft rejectDraft(String draftId, String reason) {
        return store.rejectDraft(draftId, TaskCenterStore.validateReason(reason));
    }

    public List<AgentTask> tasks(String statusFilter) {
        return store.tasks(statusFilter);
    }

    public TaskCenterStore.TaskDetail task(String taskId) {
        return store.task(taskId);
    }

    public AgentTask createBacktestTask(String draftId, String datasetId) {
        return store.createBacktestTask(draftId, datasetId);
    }

    public AgentTask approveTask(String taskId, String reason) {
        return store.approveTask(taskId, TaskCenterStore.validateReason(reason));
    }

    public AgentTask rejectTask(String taskId, String reason) {
        return store.rejectTask(taskId, TaskCenterStore.validateReason(reason));
    }

    public TaskCenterStore.TaskStartResult startTask(String taskId) {
        return store.startTask(taskId);
    }

    public AgentTask cancelTask(String taskId, String reason) {
        return store.cancelTask(taskId, TaskCenterStore.validateReason(reason));
    }

    public AgentTask retryTask(String taskId) {
        return store.retryTask(taskId);
    }

    public TaskCenterStore.AgentRunDetail agentRun(String runId) {
        return store.agentRun(runId);
    }

    public BacktestRun backtestRun(String runId) {
        return store.backtestRun(runId);
    }

    public TaskCenterStore.TaskAudit taskAudit(String taskId) {
        return store.taskAudit(taskId);
    }

    // ------------------------------------------------------------------ //

    /** The CURRENT workspace id (server state, used by response envelopes). */
    public String storeWorkspaceId() {
        return store.currentWorkspaceId();
    }

    private String currentRole() {
        var actor = teamStore.currentActor();
        return actor == null ? TeamStore.ROLE_VIEWER : actor.role();
    }

    private String currentMemberId() {
        var actor = teamStore.currentActor();
        return actor == null ? null : actor.id();
    }

    public record TurnOutcome(ConversationTurn userTurn, ConversationTurn assistantTurn,
                              AgentRun run, List<AgentStep> steps, RunCheckpoint checkpoint,
                              boolean modelUnavailable, StrategySpecDraft updatedDraft) {}

    public record SeedDecisionOutcome(StrategySeed seed, StrategySpecDraft draft) {}
}
