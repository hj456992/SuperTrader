package com.supertrader.demo.taskcenter;

import java.util.function.BooleanSupplier;

/**
 * The application-layer request to the unique Agent Runtime Harness's new
 * {@code executeTurn(HarnessTurnRequest, HarnessObserver)} entry (Task 4).
 *
 * <p>It carries ONLY server-owned inputs: the server-generated {@code runId},
 * the owning session, the validated user content, the active Draft, the
 * pending question, the actor role, the fixed server budget, a cooperative
 * cancel token, and the model {@link IntentInferencePort}. None of these come
 * from a client; the Demo controller builds this from server state.
 *
 * <p>There is deliberately NO client-supplied model / base URL / system prompt
 * / capability / budget override field anywhere here.
 */
public final class HarnessTurnRequest {

    private final String runId;
    private final ConversationSession session;
    private final String content;
    private final StrategySpecDraft activeDraft;
    private final TurnQuestion pendingQuestion;
    private final String actorRole;
    private final RunBudget budget;
    private final BooleanSupplier cancelled;
    private final IntentInferencePort intentPort;

    private HarnessTurnRequest(Builder b) {
        this.runId = b.runId;
        this.session = b.session;
        this.content = b.content;
        this.activeDraft = b.activeDraft;
        this.pendingQuestion = b.pendingQuestion;
        this.actorRole = b.actorRole;
        this.budget = b.budget == null ? BudgetPolicy.defaultBudget() : b.budget;
        this.cancelled = b.cancelled == null ? () -> false : b.cancelled;
        this.intentPort = b.intentPort == null ? IntentInferencePort.unavailable() : b.intentPort;
    }

    public String runId() { return runId; }
    public ConversationSession session() { return session; }
    public String content() { return content; }
    public StrategySpecDraft activeDraft() { return activeDraft; }
    public TurnQuestion pendingQuestion() { return pendingQuestion; }
    public String actorRole() { return actorRole; }
    public RunBudget budget() { return budget; }
    public BooleanSupplier cancelled() { return cancelled; }
    public boolean isCancelled() { return cancelled.getAsBoolean(); }
    public IntentInferencePort intentPort() { return intentPort; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String runId;
        private ConversationSession session;
        private String content;
        private StrategySpecDraft activeDraft;
        private TurnQuestion pendingQuestion;
        private String actorRole;
        private RunBudget budget;
        private BooleanSupplier cancelled;
        private IntentInferencePort intentPort;

        public Builder runId(String runId) { this.runId = runId; return this; }
        public Builder session(ConversationSession session) { this.session = session; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder activeDraft(StrategySpecDraft activeDraft) { this.activeDraft = activeDraft; return this; }
        public Builder pendingQuestion(TurnQuestion pendingQuestion) { this.pendingQuestion = pendingQuestion; return this; }
        public Builder actorRole(String actorRole) { this.actorRole = actorRole; return this; }
        public Builder budget(RunBudget budget) { this.budget = budget; return this; }
        public Builder cancelled(BooleanSupplier cancelled) { this.cancelled = cancelled; return this; }
        public Builder intentPort(IntentInferencePort intentPort) { this.intentPort = intentPort; return this; }

        public HarnessTurnRequest build() {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("runId is required (server-generated)");
            }
            if (session == null) {
                throw new IllegalArgumentException("session is required");
            }
            if (content == null) {
                throw new IllegalArgumentException("content is required");
            }
            if (actorRole == null || actorRole.isBlank()) {
                this.actorRole = "VIEWER";
            }
            return new HarnessTurnRequest(this);
        }
    }
}
