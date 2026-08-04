package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A local strategy co-creation conversation session (Module 9).
 *
 * <p>Allowed fields (the strict whitelist enforced on load and on every
 * response): id / workspaceId / title / status / createdByMemberId / createdAt /
 * updatedAt. There is deliberately NO credential, account, order/cancel or any
 * trading field anywhere in this object. Sessions are strictly scoped to the
 * CURRENT workspace; a session of another workspace is deliberately "not
 * found" (404, no existence leak).
 */
public record ConversationSession(
        @JsonProperty("id") String id,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("title") String title,
        @JsonProperty("status") String status,
        @JsonProperty("createdByMemberId") String createdByMemberId,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";

    @JsonCreator
    public ConversationSession {}
}
