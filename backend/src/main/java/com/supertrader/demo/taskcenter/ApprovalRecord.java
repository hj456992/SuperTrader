package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A human approval / rejection record (Module 9).
 *
 * <p>Only OWNER / ADMIN may approve or reject; TRADER never approves. Both the
 * decision and the reason are recorded. In this local single-machine demo a
 * user may approve their OWN request — that is allowed but MUST be recorded as
 * {@code selfApproval=true} and can never be disguised as a two-person
 * approval. EVENT evidence confirmation is a distinct decision and can never
 * be confused with Draft approval. Entity types: {@link #ENTITY_DRAFT} /
 * {@link #ENTITY_TASK} / {@link #ENTITY_EVENT_EVIDENCE}.
 */
public record ApprovalRecord(
        @JsonProperty("id") String id,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("entityType") String entityType,
        @JsonProperty("entityId") String entityId,
        @JsonProperty("decision") String decision,
        @JsonProperty("decidedByMemberId") String decidedByMemberId,
        @JsonProperty("reason") String reason,
        @JsonProperty("selfApproval") boolean selfApproval,
        @JsonProperty("decidedAt") String decidedAt) {

    public static final String ENTITY_DRAFT = "DRAFT";
    public static final String ENTITY_TASK = "TASK";
    public static final String ENTITY_EVENT_EVIDENCE = "EVENT_EVIDENCE";

    public static final String DECISION_APPROVED = "APPROVED";
    public static final String DECISION_REJECTED = "REJECTED";
    public static final String DECISION_CONFIRMED = "CONFIRMED";

    @JsonCreator
    public ApprovalRecord {}
}
