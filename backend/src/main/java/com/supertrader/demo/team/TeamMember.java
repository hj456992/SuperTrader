package com.supertrader.demo.team;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A local team member — NON-SENSITIVE metadata only (Module 6).
 *
 * Deliberately carries NO credential, token, private key, account or password
 * field of any kind: only {@code id}, {@code workspaceId}, {@code displayName},
 * {@code role}, {@code createdAt}, {@code updatedAt} and {@code active}. The
 * member belongs to exactly ONE workspace and is only ever visible through that
 * workspace's API (strict per-workspace isolation, enforced server-side).
 *
 * @param id          stable unique id (a UUID; the auto-created initial owner
 *                    also gets a UUID, one per workspace)
 * @param workspaceId the owning workspace id (members are strictly scoped to it)
 * @param displayName display name (server-validated, trimmed, 1..40 chars)
 * @param role        one of OWNER / ADMIN / TRADER / VIEWER
 * @param createdAt   ISO-8601 UTC creation time
 * @param updatedAt   ISO-8601 UTC last-modification time (== createdAt on create)
 * @param active      whether the member is currently active (停用/恢复)
 */
public record TeamMember(
        @JsonProperty("id") String id,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("role") String role,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("active") boolean active) {

    @JsonCreator
    public TeamMember {}
}
