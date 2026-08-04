package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single append-only audit event (Module 9).
 *
 * <p>Audit events are APPEND-ONLY: they are never dropped, repaired, edited or
 * rewritten by the load repair — not even when their workspace no longer
 * exists. Every state-changing operation appends an audit event; GET / page
 * loads never produce one. {@code detail} is a short sanitised Chinese
 * description (no credential-shaped content, no raw content of user messages).
 */
public record AuditEvent(
        @JsonProperty("id") String id,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("actorMemberId") String actorMemberId,
        @JsonProperty("action") String action,
        @JsonProperty("entityType") String entityType,
        @JsonProperty("entityId") String entityId,
        @JsonProperty("detail") String detail,
        @JsonProperty("createdAt") String createdAt) {

    @JsonCreator
    public AuditEvent {}
}
