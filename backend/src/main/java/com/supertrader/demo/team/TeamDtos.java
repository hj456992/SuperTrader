package com.supertrader.demo.team;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request/response DTOs for the Module 6 team API.
 *
 * Every DTO carries ONLY non-sensitive team metadata — there is no credential,
 * token, account or trading field anywhere in this API. No DTO accepts or
 * returns any trading instruction, and NO request body may carry a workspaceId:
 * the server always derives the workspace and the current actor from its own
 * state, never from the client.
 */
public final class TeamDtos {

    private TeamDtos() {}

    /** {@code POST /api/v1/teams/members} — create a member with a name + role. */
    public record CreateRequest(
            @JsonProperty("displayName") String displayName,
            @JsonProperty("role") String role) {
        @JsonCreator
        public CreateRequest {}
    }

    /** {@code PATCH /api/v1/teams/members/{id}} — change a member's role. */
    public record RoleRequest(@JsonProperty("role") String role) {
        @JsonCreator
        public RoleRequest {}
    }

    /** {@code POST /api/v1/teams/members/{id}/active} — activate / deactivate. */
    public record ActiveRequest(@JsonProperty("active") Boolean active) {
        @JsonCreator
        public ActiveRequest {}
    }

    /** {@code PUT /api/v1/teams/current-actor} — switch the local current actor. */
    public record ActorSwitchRequest(@JsonProperty("memberId") String memberId) {
        @JsonCreator
        public ActorSwitchRequest {}
    }

    /** {@code GET /api/v1/teams/members} — members of the CURRENT workspace. */
    public record MembersResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("members") List<TeamMember> members) {}

    /** Single-member responses (create / role change / active toggle). */
    public record MemberResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("member") TeamMember member) {}

    /** Current-actor responses (GET / PUT current-actor). */
    public record ActorResponse(
            @JsonProperty("schema") String schema,
            @JsonProperty("workspaceId") String workspaceId,
            @JsonProperty("actor") TeamMember actor) {}

    /** Uniform error envelope. */
    public record ErrorResponse(@JsonProperty("error") ErrorBody error) {}

    public record ErrorBody(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {}
}
