package com.supertrader.demo.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supertrader.demo.workspace.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TeamStore} — local team members + server-side RBAC.
 * Covers: auto-created initial OWNER per workspace, per-workspace isolation,
 * safe recovery from a missing / empty / corrupt store file, the FULL
 * OWNER/ADMIN/TRADER/VIEWER RBAC matrix, last-OWNER protection, actor
 * switching, restart persistence, and the non-sensitive DTO shape. All tests
 * run against scratch temp files; the real rebuild/.run/ is never touched.
 */
class TeamStoreTest {

    @TempDir
    Path tempDir;

    private Path wsFile;
    private Path teamFile;
    private WorkspaceStore wsStore;

    @BeforeEach
    void setUp() {
        wsFile = tempDir.resolve("workspaces.json");
        teamFile = tempDir.resolve("teams.json");
        wsStore = new WorkspaceStore(wsFile.toString());
    }

    private TeamStore store() {
        return new TeamStore(teamFile.toString(), wsStore);
    }

    /** Create a workspace and switch to it (returns its id). */
    private String newWorkspace(String name) {
        String id = wsStore.create(name).id();
        wsStore.switchTo(id);
        return id;
    }

    private TeamMember owner(List<TeamMember> members) {
        return members.stream().filter(m -> TeamStore.ROLE_OWNER.equals(m.role())).findFirst().orElseThrow();
    }

    private TeamMember byName(List<TeamMember> members, String name) {
        return members.stream().filter(m -> m.displayName().equals(name)).findFirst().orElseThrow();
    }

    private TeamApiException expectTeamException(TeamStore store, Runnable action) {
        return assertThrows(TeamApiException.class, action::run);
    }

    // ------------------------------------------------------------------ //
    // Initial OWNER creation (per workspace, on first access)
    // ------------------------------------------------------------------ //

    @Test
    void missingFileCreatesInitialOwnerForDefaultWorkspace() {
        TeamStore store = store();
        List<TeamMember> members = store.listMembers();
        assertEquals(1, members.size());
        TeamMember owner = members.get(0);
        assertEquals(TeamStore.ROLE_OWNER, owner.role());
        assertEquals(TeamStore.DEFAULT_OWNER_NAME, owner.displayName());
        assertTrue(owner.active());
        assertEquals("default", owner.workspaceId());
        assertNotNull(owner.id());
        assertNotNull(owner.createdAt());
        assertNotNull(owner.updatedAt());
        // The repair is persisted, so the file now exists.
        assertTrue(Files.exists(teamFile));
        // The current actor is exactly that initial OWNER.
        assertEquals(owner.id(), store.currentActor().id());
    }

    @Test
    void firstAccessToNewWorkspaceAutoCreatesItsOwnInitialOwner() {
        TeamStore store = store();
        String ws1OwnerId = store.currentActor().id();
        String ws2 = newWorkspace("策略实验室");
        List<TeamMember> members = store.listMembers();
        assertEquals(1, members.size(), "the new workspace has its OWN member list");
        TeamMember owner = members.get(0);
        assertEquals(ws2, owner.workspaceId());
        assertEquals(TeamStore.ROLE_OWNER, owner.role());
        assertNotEquals(ws1OwnerId, owner.id(), "each workspace gets its own initial OWNER");
        // And the actor switched together with the workspace.
        assertEquals(owner.id(), store.currentActor().id());
        // The default workspace's OWNER is untouched.
        wsStore.switchTo("default");
        List<TeamMember> back = store.listMembers();
        assertEquals(1, back.size());
        assertEquals(ws1OwnerId, back.get(0).id());
    }

    // ------------------------------------------------------------------ //
    // Safe recovery: missing / empty / corrupt / bad entries
    // ------------------------------------------------------------------ //

    @Test
    void emptyFileRepairsToInitialOwner() throws Exception {
        Files.writeString(teamFile, "");
        TeamStore store = store();
        assertEquals(1, store.listMembers().size());
        assertEquals(TeamStore.ROLE_OWNER, store.listMembers().get(0).role());
    }

    @Test
    void corruptFileRepairsToInitialOwner() throws Exception {
        Files.writeString(teamFile, "{{{ definitely not json \n 123");
        TeamStore store = store();
        assertEquals(1, store.listMembers().size());
        assertEquals(TeamStore.DEFAULT_OWNER_NAME, store.listMembers().get(0).displayName());
        // File was rebuilt healthy.
        JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(teamFile));
        assertEquals("teams.v1", root.path("schema").asText());
        assertTrue(root.path("members").isArray());
        assertTrue(root.path("currentActors").isObject());
    }

    @Test
    void unreadableEntriesAreDroppedButRemainingOnesKept() throws Exception {
        // One VALID owner plus one invalid entry (null role). The store must
        // drop the invalid one, keep the valid one, and never crash.
        String json = "{\"schema\":\"teams.v1\",\"currentActors\":{\"default\":\"m-valid\"},"
                + "\"members\":["
                + "{\"id\":\"m-broken\",\"workspaceId\":\"default\",\"displayName\":\"坏\",\"role\":null,"
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"active\":true},"
                + "{\"id\":\"m-valid\",\"workspaceId\":\"default\",\"displayName\":\"保留\",\"role\":\"OWNER\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"active\":true}"
                + "]}";
        Files.writeString(teamFile, json);
        TeamStore reopened = store();
        List<TeamMember> members = reopened.listMembers();
        assertEquals(1, members.size());
        assertEquals("m-valid", members.get(0).id());
        assertEquals("保留", members.get(0).displayName());
        assertEquals("m-valid", reopened.currentActor().id());
    }

    @Test
    void corruptActorPointerIsRepairedToFirstActiveOwner() throws Exception {
        // A VALID file whose actor pointer references a member of ANOTHER
        // workspace (or a missing member): repair must fall back to the first
        // active OWNER of the workspace.
        String json = "{\"schema\":\"teams.v1\",\"currentActors\":{\"default\":\"m-missing\"},"
                + "\"members\":["
                + "{\"id\":\"m-owner\",\"workspaceId\":\"default\",\"displayName\":\"本地所有者\",\"role\":\"OWNER\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"active\":true}"
                + "]}";
        Files.writeString(teamFile, json);
        TeamStore reopened = store();
        assertEquals("m-owner", reopened.currentActor().id(),
                "actor falls back to the initial OWNER");
        assertEquals(1, reopened.listMembers().size());
    }

    // ------------------------------------------------------------------ //
    // Strict per-workspace isolation
    // ------------------------------------------------------------------ //

    @Test
    void membersAreScopedToTheCurrentWorkspaceOnly() {
        TeamStore store = store();
        String ws1 = "default";
        store.createMember("交易员甲", TeamStore.ROLE_TRADER);
        String ws2 = newWorkspace("策略实验室");
        // The lab workspace sees ONLY its own OWNER — the trader from ws1 is invisible.
        List<TeamMember> lab = store.listMembers();
        assertEquals(1, lab.size());
        assertEquals(ws2, lab.get(0).workspaceId());
        assertNotEquals(ws1, lab.get(0).workspaceId());
    }

    @Test
    void crossWorkspaceMemberIdIsNotFoundForRoleChange() {
        TeamStore store = store();
        String traderId = store.createMember("交易员甲", TeamStore.ROLE_TRADER).id();
        newWorkspace("策略实验室");
        TeamApiException e = expectTeamException(store,
                () -> store.updateRole(traderId, TeamStore.ROLE_VIEWER));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals(TeamApiException.CODE_MEMBER_NOT_FOUND, e.code());
    }

    @Test
    void crossWorkspaceMemberIdIsNotFoundForActivationChange() {
        TeamStore store = store();
        String traderId = store.createMember("交易员甲", TeamStore.ROLE_TRADER).id();
        newWorkspace("策略实验室");
        TeamApiException e = expectTeamException(store, () -> store.setActive(traderId, false));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals(TeamApiException.CODE_MEMBER_NOT_FOUND, e.code());
    }

    @Test
    void crossWorkspaceMemberIdIsNotFoundForActorSwitch() {
        TeamStore store = store();
        String traderId = store.createMember("交易员甲", TeamStore.ROLE_TRADER).id();
        newWorkspace("策略实验室");
        TeamApiException e = expectTeamException(store, () -> store.switchActor(traderId));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals(TeamApiException.CODE_MEMBER_NOT_FOUND, e.code());
    }

    // ------------------------------------------------------------------ //
    // OWNER capabilities
    // ------------------------------------------------------------------ //

    @Test
    void ownerCreatesAdminTraderAndViewer() {
        TeamStore store = store();
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        TeamMember viewer = store.createMember("观察员", TeamStore.ROLE_VIEWER);
        assertEquals(TeamStore.ROLE_ADMIN, admin.role());
        assertEquals(TeamStore.ROLE_TRADER, trader.role());
        assertEquals(TeamStore.ROLE_VIEWER, viewer.role());
        assertTrue(admin.active() && trader.active() && viewer.active());
        assertEquals(4, store.listMembers().size());
    }

    @Test
    void ownerCannotCreateOwner() {
        TeamStore store = store();
        TeamApiException e = expectTeamException(store,
                () -> store.createMember("另一个所有者", TeamStore.ROLE_OWNER));
        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertEquals(TeamApiException.CODE_FORBIDDEN, e.code());
        assertEquals(1, store.listMembers().size(), "nothing was created");
    }

    @Test
    void duplicateDisplayNameIsRejectedWithinWorkspace() {
        TeamStore store = store();
        store.createMember("交易员甲", TeamStore.ROLE_TRADER);
        TeamApiException e = expectTeamException(store,
                () -> store.createMember("交易员甲", TeamStore.ROLE_VIEWER));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(TeamApiException.CODE_DUPLICATE_MEMBER, e.code());
        assertEquals(2, store.listMembers().size(), "duplicate must not add a member");
    }

    @Test
    void sameDisplayNameInDifferentWorkspacesIsAllowed() {
        TeamStore store = store();
        store.createMember("交易员甲", TeamStore.ROLE_TRADER);
        newWorkspace("策略实验室");
        // Same display name is fine in ANOTHER workspace.
        assertEquals(TeamStore.ROLE_TRADER, store.createMember("交易员甲", TeamStore.ROLE_TRADER).role());
        assertEquals(2, store.listMembers().size());
    }

    @Test
    void invalidDisplayNameAndRoleAreRejected() {
        TeamStore store = store();
        TeamApiException blank = expectTeamException(store,
                () -> store.createMember("   ", TeamStore.ROLE_TRADER));
        assertEquals(HttpStatus.BAD_REQUEST, blank.status());
        assertEquals(TeamApiException.CODE_INVALID_NAME, blank.code());

        TeamApiException longName = expectTeamException(store,
                () -> store.createMember("a".repeat(TeamStore.MAX_DISPLAY_NAME + 1), TeamStore.ROLE_TRADER));
        assertEquals(HttpStatus.BAD_REQUEST, longName.status());

        TeamApiException badRole = expectTeamException(store,
                () -> store.createMember("某人", "SUPERADMIN"));
        assertEquals(HttpStatus.BAD_REQUEST, badRole.status());
        assertEquals(TeamApiException.CODE_INVALID_ROLE, badRole.code());
    }

    @Test
    void ownerUpdatesAnyNonSelfRole() {
        TeamStore store = store();
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        assertEquals(TeamStore.ROLE_VIEWER, store.updateRole(admin.id(), TeamStore.ROLE_VIEWER).role());
        assertEquals(TeamStore.ROLE_ADMIN, store.updateRole(trader.id(), TeamStore.ROLE_ADMIN).role());
    }

    @Test
    void ownerCannotUpdateOwnRole() {
        TeamStore store = store();
        // Create a SECOND active OWNER so the operation is NOT the
        // last-owner-guard path — it must be rejected as "non-self" 403.
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        store.updateRole(admin.id(), TeamStore.ROLE_OWNER);
        String ownerId = store.currentActor().id();
        TeamApiException e = expectTeamException(store,
                () -> store.updateRole(ownerId, TeamStore.ROLE_ADMIN));
        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertEquals(TeamApiException.CODE_FORBIDDEN, e.code());
    }

    @Test
    void ownerTogglesNonSelfActiveState() {
        TeamStore store = store();
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        assertFalse(store.setActive(trader.id(), false).active());
        assertTrue(store.setActive(trader.id(), true).active());
    }

    @Test
    void ownerCannotToggleOwnActiveState() {
        TeamStore store = store();
        // Create a SECOND active OWNER so the operation is NOT the
        // last-owner-guard path — it must be rejected as "non-self" 403.
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        store.updateRole(admin.id(), TeamStore.ROLE_OWNER);
        String ownerId = store.currentActor().id();
        TeamApiException e = expectTeamException(store, () -> store.setActive(ownerId, false));
        assertEquals(HttpStatus.FORBIDDEN, e.status());
    }

    @Test
    void ownerPromotesAndDemotesAnotherOwner() {
        TeamStore store = store();
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        // Promote the ADMIN to OWNER -> two active OWNERs.
        assertEquals(TeamStore.ROLE_OWNER, store.updateRole(admin.id(), TeamStore.ROLE_OWNER).role());
        assertEquals(2, store.listMembers().stream()
                .filter(m -> TeamStore.ROLE_OWNER.equals(m.role()) && m.active()).count());
        // Demote the OTHER OWNER back -> the actor (initial OWNER) still exists.
        assertEquals(TeamStore.ROLE_ADMIN, store.updateRole(admin.id(), TeamStore.ROLE_ADMIN).role());
        assertEquals(1, store.listMembers().stream()
                .filter(m -> TeamStore.ROLE_OWNER.equals(m.role()) && m.active()).count());
    }

    @Test
    void demotingLastActiveOwnerIsRejected() {
        TeamStore store = store();
        String ownerId = store.currentActor().id();
        // The sole active OWNER (also the actor) tries to demote itself:
        // LAST_OWNER_PROTECTED is more specific than the generic non-self 403.
        TeamApiException e = expectTeamException(store,
                () -> store.updateRole(ownerId, TeamStore.ROLE_ADMIN));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(TeamApiException.CODE_LAST_OWNER_PROTECTED, e.code());
        assertEquals(TeamStore.ROLE_OWNER, store.listMembers().get(0).role(), "role unchanged");
    }

    @Test
    void deactivatingLastActiveOwnerIsRejected() {
        TeamStore store = store();
        String ownerId = store.currentActor().id();
        TeamApiException e = expectTeamException(store, () -> store.setActive(ownerId, false));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(TeamApiException.CODE_LAST_OWNER_PROTECTED, e.code());
        assertTrue(store.listMembers().get(0).active(), "still active");
    }

    @Test
    void deactivatingOtherOwnerKeepsActorUnchanged() {
        TeamStore store = store();
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        TeamMember secondOwner = store.updateRole(admin.id(), TeamStore.ROLE_OWNER);
        String actorId = store.currentActor().id();   // the initial OWNER
        assertNotEquals(secondOwner.id(), actorId);
        // The actor (an OWNER) deactivates the OTHER OWNER; the actor is untouched.
        assertFalse(store.setActive(secondOwner.id(), false).active());
        assertEquals(actorId, store.currentActor().id());
        // Deactivated OWNERs can never become the actor again.
        TeamApiException e = expectTeamException(store, () -> store.switchActor(secondOwner.id()));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(TeamApiException.CODE_INACTIVE_MEMBER, e.code());
    }

    // ------------------------------------------------------------------ //
    // ADMIN capabilities
    // ------------------------------------------------------------------ //

    private TeamStore storeActingAs(String roleName) {
        TeamStore store = store();
        TeamMember member = store.createMember("演示成员", roleName);
        store.switchActor(member.id());
        return store;
    }

    @Test
    void adminCreatesTraderAndViewer() {
        TeamStore store = storeActingAs(TeamStore.ROLE_ADMIN);
        assertEquals(TeamStore.ROLE_TRADER, store.createMember("交易员", TeamStore.ROLE_TRADER).role());
        assertEquals(TeamStore.ROLE_VIEWER, store.createMember("观察员", TeamStore.ROLE_VIEWER).role());
    }

    @Test
    void adminCannotCreateAdminOrOwner() {
        TeamStore store = storeActingAs(TeamStore.ROLE_ADMIN);
        TeamApiException admin = expectTeamException(store,
                () -> store.createMember("第二管理员", TeamStore.ROLE_ADMIN));
        assertEquals(HttpStatus.FORBIDDEN, admin.status());
        TeamApiException owner = expectTeamException(store,
                () -> store.createMember("第二所有者", TeamStore.ROLE_OWNER));
        assertEquals(HttpStatus.FORBIDDEN, owner.status());
        assertEquals(2, store.listMembers().size(), "nothing extra was created");
    }

    @Test
    void adminUpdatesOnlyTraderViewerAndOnlyToTraderViewer() {
        TeamStore store = storeActingAs(TeamStore.ROLE_ADMIN);
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        TeamMember viewer = store.createMember("观察员", TeamStore.ROLE_VIEWER);
        // TRADER -> VIEWER is allowed.
        assertEquals(TeamStore.ROLE_VIEWER, store.updateRole(trader.id(), TeamStore.ROLE_VIEWER).role());
        // VIEWER -> TRADER is allowed.
        assertEquals(TeamStore.ROLE_TRADER, store.updateRole(viewer.id(), TeamStore.ROLE_TRADER).role());
        // Escalation to ADMIN / OWNER is forbidden.
        TeamApiException toAdmin = expectTeamException(store,
                () -> store.updateRole(trader.id(), TeamStore.ROLE_ADMIN));
        assertEquals(HttpStatus.FORBIDDEN, toAdmin.status());
        TeamApiException toOwner = expectTeamException(store,
                () -> store.updateRole(viewer.id(), TeamStore.ROLE_OWNER));
        assertEquals(HttpStatus.FORBIDDEN, toOwner.status());
    }

    @Test
    void adminCannotTouchOwnerOrAdmin() {
        TeamStore store = store();
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        String ownerId = store.currentActor().id();
        store.switchActor(admin.id());

        TeamApiException role = expectTeamException(store,
                () -> store.updateRole(ownerId, TeamStore.ROLE_VIEWER));
        assertEquals(HttpStatus.FORBIDDEN, role.status());
        TeamApiException active = expectTeamException(store, () -> store.setActive(ownerId, false));
        assertEquals(HttpStatus.FORBIDDEN, active.status());
        TeamApiException selfRole = expectTeamException(store,
                () -> store.updateRole(admin.id(), TeamStore.ROLE_VIEWER));
        assertEquals(HttpStatus.FORBIDDEN, selfRole.status());
    }

    @Test
    void adminTogglesOnlyTraderAndViewer() {
        TeamStore store = storeActingAs(TeamStore.ROLE_ADMIN);
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        assertFalse(store.setActive(trader.id(), false).active());
        assertTrue(store.setActive(trader.id(), true).active());
    }

    // ------------------------------------------------------------------ //
    // TRADER / VIEWER capabilities (view-only)
    // ------------------------------------------------------------------ //

    @Test
    void traderHasNoManagementPower() {
        TeamStore store = storeActingAs(TeamStore.ROLE_TRADER);
        expectForbidden(store, () -> store.createMember("新成员", TeamStore.ROLE_VIEWER));
        expectForbidden(store, () -> store.updateRole(store.currentActor().id(), TeamStore.ROLE_VIEWER));
        expectForbidden(store, () -> store.setActive(store.currentActor().id(), false));
    }

    @Test
    void viewerHasNoManagementPower() {
        TeamStore store = storeActingAs(TeamStore.ROLE_VIEWER);
        expectForbidden(store, () -> store.createMember("新成员", TeamStore.ROLE_TRADER));
        expectForbidden(store, () -> store.updateRole(store.currentActor().id(), TeamStore.ROLE_TRADER));
        expectForbidden(store, () -> store.setActive(store.currentActor().id(), false));
    }

    private void expectForbidden(TeamStore store, Runnable action) {
        TeamApiException e = expectTeamException(store, action);
        assertEquals(HttpStatus.FORBIDDEN, e.status());
        assertEquals(TeamApiException.CODE_FORBIDDEN, e.code());
    }

    // ------------------------------------------------------------------ //
    // Current actor switching (demo-only local pointer, every role may switch)
    // ------------------------------------------------------------------ //

    @Test
    void anyRoleMaySwitchTheDemoActor() {
        TeamStore store = store();
        TeamMember admin = store.createMember("管理员", TeamStore.ROLE_ADMIN);
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        TeamMember viewer = store.createMember("观察员", TeamStore.ROLE_VIEWER);
        // OWNER -> ADMIN -> TRADER -> VIEWER -> back to the initial OWNER.
        assertEquals(admin.id(), store.switchActor(admin.id()).id());
        assertEquals(admin.id(), store.currentActor().id());
        assertEquals(trader.id(), store.switchActor(trader.id()).id());
        assertEquals(viewer.id(), store.switchActor(viewer.id()).id());
        String ownerId = store.listMembers().stream()
                .filter(m -> TeamStore.ROLE_OWNER.equals(m.role())).findFirst().orElseThrow().id();
        assertEquals(ownerId, store.switchActor(ownerId).id());
    }

    @Test
    void switchToUnknownOrBlankMemberIsRejected() {
        TeamStore store = store();
        TeamApiException unknown = expectTeamException(store, () -> store.switchActor("no-such"));
        assertEquals(HttpStatus.NOT_FOUND, unknown.status());
        assertEquals(TeamApiException.CODE_MEMBER_NOT_FOUND, unknown.code());
        TeamApiException blank = expectTeamException(store, () -> store.switchActor("  "));
        assertEquals(HttpStatus.BAD_REQUEST, blank.status());
        assertEquals(TeamApiException.CODE_INVALID_BODY, blank.code());
    }

    @Test
    void switchToInactiveMemberIsRejected() {
        TeamStore store = store();
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        store.setActive(trader.id(), false);
        TeamApiException e = expectTeamException(store, () -> store.switchActor(trader.id()));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(TeamApiException.CODE_INACTIVE_MEMBER, e.code());
    }

    // ------------------------------------------------------------------ //
    // Restart persistence
    // ------------------------------------------------------------------ //

    @Test
    void membersRolesAndActorSurviveRestart() {
        TeamStore first = store();
        TeamMember admin = first.createMember("管理员", TeamStore.ROLE_ADMIN);
        TeamMember trader = first.createMember("交易员", TeamStore.ROLE_TRADER);
        first.updateRole(trader.id(), TeamStore.ROLE_VIEWER);
        first.setActive(trader.id(), false);
        first.switchActor(admin.id());
        String ws2 = newWorkspace("策略实验室");
        first.createMember("实验室观察员", TeamStore.ROLE_VIEWER);
        String ws2OwnerId = first.listMembers().stream()
                .filter(m -> TeamStore.ROLE_OWNER.equals(m.role()))
                .findFirst().orElseThrow().id();

        // "Restart": a brand-new store over the same files. Current = ws2.
        TeamStore second = store();
        assertEquals(ws2OwnerId, second.currentActor().id(), "ws2 actor survives restart");
        List<TeamMember> members = second.listMembers();
        assertEquals(2, members.size(), "workspace-2 members only");
        assertEquals(TeamStore.ROLE_VIEWER, byName(members, "实验室观察员").role());

        // Back on ws1: its own data is intact, isolated and persisted.
        wsStore.switchTo("default");
        TeamStore third = store();
        assertEquals(admin.id(), third.currentActor().id(), "ws1 actor survives restart");
        List<TeamMember> ws1 = third.listMembers();
        assertEquals(3, ws1.size());
        assertTrue(ws1.stream().noneMatch(m -> m.workspaceId().equals(ws2)));
        assertEquals(TeamStore.ROLE_VIEWER, byName(ws1, "交易员").role(), "role change survives");
        assertFalse(byName(ws1, "交易员").active(), "deactivation survives");
        assertNotEquals(ws2OwnerId, third.currentActor().id());
    }

    // ------------------------------------------------------------------ //
    // DTO shape: NON-SENSITIVE metadata only
    // ------------------------------------------------------------------ //

    @Test
    void serializedMemberHasOnlyAllowedFields() throws Exception {
        TeamStore store = store();
        TeamMember trader = store.createMember("交易员", TeamStore.ROLE_TRADER);
        JsonNode node = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(trader));
        Set<String> keys = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id", "workspaceId", "displayName", "role",
                        "createdAt", "updatedAt", "active"),
                keys, "member JSON must carry ONLY the non-sensitive fields");
    }

    @Test
    void storeFileNeverContainsCredentialShapedContent() throws Exception {
        TeamStore store = store();
        store.createMember("交易员", TeamStore.ROLE_TRADER);
        store.switchActor(store.listMembers().get(0).id());
        String raw = Files.readString(teamFile);
        assertFalse(raw.contains("password"), "store file must never contain password-shaped content");
        assertFalse(raw.contains("authCode"));
        assertFalse(raw.contains("appId"));
        assertFalse(raw.contains("token"));
        assertFalse(raw.contains("secret"));
    }

    // ------------------------------------------------------------------ //
    // Persistence failure surfaces a clear error, not a crash
    // ------------------------------------------------------------------ //

    @Test
    void persistFailureThrowsClearError() throws Exception {
        // Point the store at a path whose PARENT is a FILE, so directory
        // creation fails and the write must surface a 500-style error.
        Path blocker = tempDir.resolve("blocker-file");
        Files.writeString(blocker, "x");
        TeamStore store = new TeamStore(blocker.resolve("teams.json").toString(), wsStore);
        TeamApiException e = expectTeamException(store,
                () -> store.createMember("交易员", TeamStore.ROLE_TRADER));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.status());
        assertEquals(TeamApiException.CODE_PERSIST_FAILED, e.code());
    }
}
