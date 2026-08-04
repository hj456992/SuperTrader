package com.supertrader.demo.team;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module 6 team API — local single-machine team members + server-side RBAC.
 *
 * Contract (ALL inputs are validated server-side; the workspace and the current
 * actor are ALWAYS derived from server state, never from the request body; NO
 * credential is ever returned; NO trading action is exposed; members are
 * strictly scoped to the CURRENT workspace):
 *   GET   /api/v1/teams/members            members of the current workspace
 *   POST  /api/v1/teams/members            create a member (role from matrix)
 *   PATCH /api/v1/teams/members/{id}       change a member's role
 *   POST  /api/v1/teams/members/{id}/active activate / deactivate a member
 *   GET   /api/v1/teams/current-actor      the current local actor
 *   PUT   /api/v1/teams/current-actor      switch the current local actor
 *
 * There is deliberately NO DELETE route: this module never removes members.
 * All state is persisted locally under rebuild/.run/ (Git-ignored). The current
 * actor is a demo-only local pointer — the page MUST label it
 * 「本地权限演示，不是远程登录」; it is not real authentication.
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamStore store;

    public TeamController(TeamStore store) {
        this.store = store;
    }

    @GetMapping("/members")
    public TeamDtos.MembersResponse members() {
        return new TeamDtos.MembersResponse(
                "teams.members.v1", store.currentWorkspaceId(), store.listMembers());
    }

    @PostMapping("/members")
    public ResponseEntity<TeamDtos.MemberResponse> create(
            @RequestBody TeamDtos.CreateRequest body) {
        TeamMember created = store.createMember(
                body == null ? null : body.displayName(),
                body == null ? null : body.role());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TeamDtos.MemberResponse("teams.member.v1", created));
    }

    @PatchMapping("/members/{id}")
    public TeamDtos.MemberResponse updateRole(
            @PathVariable("id") String id,
            @RequestBody TeamDtos.RoleRequest body) {
        TeamMember updated = store.updateRole(id, body == null ? null : body.role());
        return new TeamDtos.MemberResponse("teams.member.v1", updated);
    }

    @PostMapping("/members/{id}/active")
    public TeamDtos.MemberResponse setActive(
            @PathVariable("id") String id,
            @RequestBody TeamDtos.ActiveRequest body) {
        if (body == null || body.active() == null) {
            throw TeamApiException.invalidBody("请求体必须包含布尔字段 active");
        }
        TeamMember updated = store.setActive(id, body.active());
        return new TeamDtos.MemberResponse("teams.member.v1", updated);
    }

    @GetMapping("/current-actor")
    public TeamDtos.ActorResponse currentActor() {
        return new TeamDtos.ActorResponse(
                "teams.current-actor.v1", store.currentWorkspaceId(), store.currentActor());
    }

    @PutMapping("/current-actor")
    public TeamDtos.ActorResponse switchActor(@RequestBody TeamDtos.ActorSwitchRequest body) {
        TeamMember actor = store.switchActor(body == null ? null : body.memberId());
        return new TeamDtos.ActorResponse(
                "teams.current-actor.v1", store.currentWorkspaceId(), actor);
    }
}
