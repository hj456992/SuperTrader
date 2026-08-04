package com.supertrader.demo.workspace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request/response DTOs for the Module 5 workspace API.
 *
 * Every DTO carries ONLY non-sensitive workspace metadata — there is no
 * credential, token, account or trading field anywhere in this API. No DTO
 * accepts or returns any trading instruction.
 */
public final class WorkspaceDtos {

    private WorkspaceDtos() {}

    /** {@code POST /api/v1/workspaces} — create with a name. */
    public record CreateRequest(@JsonProperty("name") String name) {
        @JsonCreator
        public CreateRequest {}
    }

    /** {@code PUT /api/v1/workspaces/current} — switch by id. */
    public record SwitchRequest(@JsonProperty("id") String id) {
        @JsonCreator
        public SwitchRequest {}
    }

    /** {@code PATCH /api/v1/workspaces/{id}} — rename with a name. */
    public record RenameRequest(@JsonProperty("name") String name) {
        @JsonCreator
        public RenameRequest {}
    }

    /** {@code GET /api/v1/workspaces} — list + current selection. */
    public record WorkspaceListResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("currentWorkspaceId") String currentWorkspaceId,
            @JsonProperty("workspaces") List<Workspace> workspaces) {}

    /** Single-workspace responses (create / rename / current). */
    public record WorkspaceItemResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("workspace") Workspace workspace) {}

    /** Uniform error envelope. */
    public record ErrorResponse(@JsonProperty("error") ErrorBody error) {}

    public record ErrorBody(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {}
}
