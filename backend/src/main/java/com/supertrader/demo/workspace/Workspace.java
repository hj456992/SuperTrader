package com.supertrader.demo.workspace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A local workspace — pure NON-SENSITIVE metadata only.
 *
 * Deliberately carries NO credential, token, private key, account or password
 * field of any kind: only {@code id}, {@code name}, {@code createdAt} and
 * {@code updatedAt} (ISO-8601 UTC strings). The workspace is the Module 5
 * entry object of the local-only platform shell; it has NO trading meaning and
 * exposes NO trading capability.
 *
 * @param id        stable unique id (the default workspace uses "default";
 *                  created workspaces use a UUID)
 * @param name      display name (server-validated, trimmed, 1..40 chars)
 * @param createdAt ISO-8601 UTC creation time
 * @param updatedAt ISO-8601 UTC last-modification time (== createdAt on create)
 */
public record Workspace(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt) {

    /** The id of the always-present default workspace. */
    public static final String DEFAULT_ID = "default";

    @JsonCreator
    public Workspace {}
}
