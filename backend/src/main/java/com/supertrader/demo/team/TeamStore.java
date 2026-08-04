package com.supertrader.demo.team;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supertrader.demo.workspace.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local, disk-persisted store of team member metadata + the per-workspace local
 * current actor (Module 6). This is a SINGLE-MACHINE, DEMO-ONLY collaboration
 * model: there is no real login, no remote authentication, no invites, no
 * multi-user cloud state. The "current actor" only drives local page acceptance
 * of the RBAC behaviour and must NEVER be presented as real identity.
 *
 * Contract:
 *  - Members persist ONLY non-sensitive fields
 *    ({@code id, workspaceId, displayName, role, createdAt, updatedAt, active})
 *    to a single JSON file whose default location is {@code ../.run/teams.json}
 *    relative to the backend working dir (backend/), i.e.
 *    {@code rebuild/.run/teams.json} — the ONLY allowed location, Git-ignored.
 *  - Every workspace gets EXACTLY ONE auto-created initial OWNER ("本地所有者")
 *    on first access; the "at least one active OWNER" invariant is enforced on
 *    load AND on every mutation (409 LAST_OWNER_PROTECTED).
 *  - SAFE RECOVERY: a missing / empty / corrupt file never fails startup or any
 *    request — the store repairs it (per-workspace initial OWNER + valid actor).
 *  - Strict per-workspace isolation: every read/write is scoped to the CURRENT
 *    workspace (from {@link WorkspaceStore}); a member of another workspace is
 *    deliberately "not found" (404), so cross-workspace reads or mutations are
 *    impossible. Requests NEVER carry a workspaceId — it is always derived from
 *    server state.
 *  - ALL permission decisions are enforced HERE in the server. The frontend may
 *    hide buttons, but that is NOT access control; every write re-checks the
 *    current actor's role against the RBAC matrix.
 *  - There is NO member deletion by design (this module only adds members,
 *    switches the local current actor, changes roles, and activates/deactivates).
 *  - No trading meaning: no account/order/cancel state, NO SimNow interaction.
 *
 * Concurrency: all mutating/reading entry points are {@code synchronized}; the
 * file is read on every operation and written atomically (tmp + move).
 */
@Component
public class TeamStore {

    private static final Logger log = LoggerFactory.getLogger(TeamStore.class);

    static final String SCHEMA = "teams.v1";

    /** Fixed roles. OWNER is never creatable — the initial owner is auto-made.
     *  Public so other modules (e.g. Module 7 trading-account RBAC) can reuse
     *  the exact same role vocabulary; these are non-sensitive role names. */
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_TRADER = "TRADER";
    public static final String ROLE_VIEWER = "VIEWER";
    public static final Set<String> ROLES = new LinkedHashSet<>(
            List.of(ROLE_OWNER, ROLE_ADMIN, ROLE_TRADER, ROLE_VIEWER));

    static final int MAX_DISPLAY_NAME = 40;

    /** Display name of the auto-created initial OWNER of every workspace. */
    static final String DEFAULT_OWNER_NAME = "本地所有者";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final WorkspaceStore workspaceStore;

    public TeamStore(@Value("${app.teams.file}") String teamsFile, WorkspaceStore workspaceStore) {
        this.file = Path.of(teamsFile);
        this.workspaceStore = workspaceStore;
    }

    /** Visible for tests: the resolved store file path. */
    Path filePath() {
        return file;
    }

    /** The id of the CURRENT workspace (always derived from server state). */
    public synchronized String currentWorkspaceId() {
        return workspaceStore.currentWorkspaceId();
    }

    // ------------------------------------------------------------------ //
    // Read API (scoped to the CURRENT workspace)
    // ------------------------------------------------------------------ //

    /** Members of the current workspace only, oldest first. Never empty. */
    public synchronized List<TeamMember> listMembers() {
        Snapshot snap = loadAndRepair();
        String wsId = currentWorkspaceId();
        return snap.members().stream()
                .filter(m -> m.workspaceId().equals(wsId))
                .sorted(Comparator.comparing(TeamMember::createdAt))
                .toList();
    }

    /** The current local actor of the current workspace. Never null. */
    public synchronized TeamMember currentActor() {
        Snapshot snap = loadAndRepair();
        return currentActorInternal(snap);
    }

    // ------------------------------------------------------------------ //
    // Write API
    //
    // Every write derives the workspace + the current actor from SERVER state
    // and re-checks the RBAC matrix. A request body can never carry a
    // workspaceId, a role grant, or a member ownership claim.
    // ------------------------------------------------------------------ //

    /** Create a member (ADMIN/TRADER/VIEWER targets only; OWNER is never creatable). */
    public synchronized TeamMember createMember(String rawName, String rawRole) {
        String name = validateDisplayName(rawName);       // 400 INVALID_NAME
        String role = validateRole(rawRole);              // 400 INVALID_ROLE
        Snapshot snap = loadAndRepair();
        TeamMember actor = currentActorInternal(snap);
        String wsId = currentWorkspaceId();

        // RBAC matrix (server-side, authoritative):
        //   OWNER → ADMIN / TRADER / VIEWER; ADMIN → TRADER / VIEWER; else FORBIDDEN.
        if (ROLE_OWNER.equals(role)) {
            throw TeamApiException.forbidden(
                    "OWNER 角色只能由系统在工作空间首次访问时自动创建，任何角色都不得新增 OWNER");
        }
        if (ROLE_ADMIN.equals(actor.role())) {
            if (!(ROLE_TRADER.equals(role) || ROLE_VIEWER.equals(role))) {
                throw TeamApiException.forbidden("ADMIN 仅可新增 TRADER / VIEWER");
            }
        } else if (!ROLE_OWNER.equals(actor.role())) {
            throw TeamApiException.forbidden("当前角色（" + actor.role() + "）无新增成员权限");
        }
        ensureDisplayNameFree(snap, wsId, name, null);    // 409 DUPLICATE_MEMBER

        String now = Instant.now().toString();
        TeamMember created = new TeamMember(UUID.randomUUID().toString(), wsId,
                name, role, now, now, true);
        List<TeamMember> next = new ArrayList<>(snap.members());
        next.add(created);
        persist(new Snapshot(next, snap.currentActors()));
        return created;
    }

    /** Change a member's role (server-side RBAC + last-owner guard). */
    public synchronized TeamMember updateRole(String memberId, String rawRole) {
        String role = validateRole(rawRole);              // 400 INVALID_ROLE
        Snapshot snap = loadAndRepair();
        TeamMember actor = currentActorInternal(snap);
        String wsId = currentWorkspaceId();
        TeamMember target = byIdOrThrow(snap, wsId, memberId);   // 404 (incl. cross-workspace)

        // RBAC matrix:
        //   OWNER → any non-self member, any legal role (but never the last
        //            active OWNER);
        //   ADMIN → TRADER / VIEWER members only, TRADER / VIEWER targets only;
        //   else  FORBIDDEN.
        if (ROLE_ADMIN.equals(actor.role())) {
            if (!(ROLE_TRADER.equals(target.role()) || ROLE_VIEWER.equals(target.role()))) {
                throw TeamApiException.forbidden("ADMIN 仅可调整 TRADER / VIEWER 的角色");
            }
            if (!(ROLE_TRADER.equals(role) || ROLE_VIEWER.equals(role))) {
                throw TeamApiException.forbidden("ADMIN 只能将 TRADER / VIEWER 调整为 TRADER / VIEWER");
            }
        } else if (!ROLE_OWNER.equals(actor.role())) {
            throw TeamApiException.forbidden("当前角色（" + actor.role() + "）无调整成员角色权限");
        }

        // Last-owner guard (OWNER branch): demoting an active OWNER must never
        // leave the workspace without an active OWNER. Checked BEFORE the
        // "non-self" rule so the sole OWNER demoting itself gets the specific
        // 409 instead of a generic 403.
        boolean demotesOwner = ROLE_OWNER.equals(target.role()) && target.active()
                && !ROLE_OWNER.equals(role);
        if (demotesOwner && activeOwnerCountAfter(snap, wsId, memberId, false) == 0) {
            throw TeamApiException.lastOwnerProtected("不得降级最后一个 active OWNER");
        }
        if (ROLE_OWNER.equals(actor.role()) && target.id().equals(actor.id())) {
            throw TeamApiException.forbidden("OWNER 不能修改自身的角色");
        }

        String now = Instant.now().toString();
        TeamMember updated = new TeamMember(target.id(), target.workspaceId(),
                target.displayName(), role, target.createdAt(), now, target.active());
        List<TeamMember> next = new ArrayList<>(snap.members());
        next.replaceAll(m -> m.id().equals(memberId) ? updated : m);
        persist(new Snapshot(next, snap.currentActors()));
        return updated;
    }

    /** Activate / deactivate a member (server-side RBAC + last-owner guard). */
    public synchronized TeamMember setActive(String memberId, boolean active) {
        Snapshot snap = loadAndRepair();
        TeamMember actor = currentActorInternal(snap);
        String wsId = currentWorkspaceId();
        TeamMember target = byIdOrThrow(snap, wsId, memberId);   // 404 (incl. cross-workspace)

        // RBAC matrix:
        //   OWNER → any non-self member;
        //   ADMIN → TRADER / VIEWER members only;
        //   else  FORBIDDEN.
        if (ROLE_ADMIN.equals(actor.role())) {
            if (!(ROLE_TRADER.equals(target.role()) || ROLE_VIEWER.equals(target.role()))) {
                throw TeamApiException.forbidden("ADMIN 仅可停用/恢复 TRADER / VIEWER");
            }
        } else if (!ROLE_OWNER.equals(actor.role())) {
            throw TeamApiException.forbidden("当前角色（" + actor.role() + "）无停用/恢复成员权限");
        }

        // Last-owner guard (OWNER branch), checked before the "non-self" rule
        // for the same reason as updateRole.
        boolean deactivatesOwner = !active && ROLE_OWNER.equals(target.role()) && target.active();
        if (deactivatesOwner && activeOwnerCountAfter(snap, wsId, memberId, false) == 0) {
            throw TeamApiException.lastOwnerProtected("不得停用最后一个 active OWNER");
        }
        if (ROLE_OWNER.equals(actor.role()) && target.id().equals(actor.id())) {
            throw TeamApiException.forbidden("OWNER 不能停用/恢复自身");
        }

        String now = Instant.now().toString();
        TeamMember updated = new TeamMember(target.id(), target.workspaceId(),
                target.displayName(), target.role(), target.createdAt(), now, active);
        List<TeamMember> next = new ArrayList<>(snap.members());
        next.replaceAll(m -> m.id().equals(memberId) ? updated : m);
        Map<String, String> actors = new HashMap<>(snap.currentActors());
        // Deactivating the CURRENT local actor resets it to the first active
        // OWNER of the workspace (本地所有者 semantics), so the actor is always
        // an active member.
        if (!active && memberId.equals(actors.get(wsId))) {
            actors.put(wsId, firstActiveOwner(next, wsId).id());
        }
        persist(new Snapshot(next, actors));
        return updated;
    }

    /**
     * Switch the local current actor of the current workspace. The actor is a
     * DEMO-ONLY local pointer (页面标注「本地权限演示，不是远程登录」) used to
     * exercise the RBAC behaviour in the browser; switching it never changes
     * member data, so every role may switch (the page only shows the switcher
     * to OWNER/ADMIN). The target must be an ACTIVE member of the CURRENT
     * workspace (404 otherwise; 409 when inactive).
     */
    public synchronized TeamMember switchActor(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw TeamApiException.invalidBody("切换请求必须包含非空的 memberId");
        }
        Snapshot snap = loadAndRepair();
        String wsId = currentWorkspaceId();
        TeamMember target = byIdOrThrow(snap, wsId, memberId);
        if (!target.active()) {
            throw TeamApiException.inactiveMember(
                    "停用成员不能成为本地当前操作者：" + target.displayName());
        }
        Map<String, String> actors = new HashMap<>(snap.currentActors());
        actors.put(wsId, memberId);
        persist(new Snapshot(snap.members(), actors));
        return target;
    }

    // ------------------------------------------------------------------ //
    // Server-side validation
    // ------------------------------------------------------------------ //

    /**
     * Validate a display name: required, trimmed, 1..{@value #MAX_DISPLAY_NAME}
     * chars, no control characters. Returns the trimmed name or throws
     * {@link TeamApiException#invalidName}.
     */
    static String validateDisplayName(String rawName) {
        if (rawName == null) {
            throw TeamApiException.invalidName("成员名称不能为空");
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            throw TeamApiException.invalidName("成员名称不能为空");
        }
        if (name.length() > MAX_DISPLAY_NAME) {
            throw TeamApiException.invalidName(
                    "成员名称过长（最多 " + MAX_DISPLAY_NAME + " 个字符）");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw TeamApiException.invalidName("成员名称包含不允许的控制字符");
            }
        }
        return name;
    }

    /** Validate a role: must be exactly one of the four fixed roles. */
    static String validateRole(String rawRole) {
        if (rawRole == null || !ROLES.contains(rawRole)) {
            throw TeamApiException.invalidRole(
                    "角色必须是 OWNER / ADMIN / TRADER / VIEWER 之一");
        }
        return rawRole;
    }

    /** Duplicate = same trimmed display name (ASCII case-insensitive) in the SAME workspace. */
    private static void ensureDisplayNameFree(Snapshot snap, String wsId, String name, String selfId) {
        for (TeamMember m : snap.members()) {
            if (m.workspaceId().equals(wsId)
                    && !m.id().equals(selfId)
                    && m.displayName().equalsIgnoreCase(name)) {
                throw TeamApiException.duplicateMember("当前工作空间已存在同名成员：" + m.displayName());
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Snapshot load + repair (missing / empty / corrupt -> safe defaults)
    // ------------------------------------------------------------------ //

    private record Snapshot(List<TeamMember> members, Map<String, String> currentActors) {}

    /** How many active OWNERs remain in the workspace after one member is changed. */
    private static long activeOwnerCountAfter(Snapshot snap, String wsId,
                                              String changedId, boolean changedIsActiveOwner) {
        long owners = 0;
        for (TeamMember m : snap.members()) {
            if (!m.workspaceId().equals(wsId)) continue;
            if (m.id().equals(changedId)) {
                if (changedIsActiveOwner) owners++;
            } else if (ROLE_OWNER.equals(m.role()) && m.active()) {
                owners++;
            }
        }
        return owners;
    }

    private TeamMember currentActorInternal(Snapshot snap) {
        String wsId = currentWorkspaceId();
        TeamMember actor = byId(snap, wsId, snap.currentActors().get(wsId));
        if (actor == null) {
            // Defensive: repair guarantees an active OWNER actor, so this is a
            // programming-error canary, never a user-facing state.
            throw new IllegalStateException("repair must guarantee an active current actor");
        }
        return actor;
    }

    private TeamMember byId(Snapshot snap, String wsId, String memberId) {
        for (TeamMember m : snap.members()) {
            if (m.id().equals(memberId) && m.workspaceId().equals(wsId)) return m;
        }
        // Defensive fallback after repair: the first active OWNER of the ws.
        return firstActiveOwner(snap.members(), wsId);
    }

    private TeamMember byIdOrThrow(Snapshot snap, String wsId, String memberId) {
        for (TeamMember m : snap.members()) {
            if (m.id().equals(memberId) && m.workspaceId().equals(wsId)) return m;
        }
        // A member of ANOTHER workspace is deliberately "not found" here — this
        // is the server-side isolation boundary.
        throw TeamApiException.memberNotFound("当前工作空间不存在该成员：" + memberId);
    }

    private static TeamMember firstActiveOwner(List<TeamMember> members, String wsId) {
        return members.stream()
                .filter(m -> m.workspaceId().equals(wsId))
                .filter(m -> ROLE_OWNER.equals(m.role()))
                .filter(TeamMember::active)
                .min(Comparator.comparing(TeamMember::createdAt))
                .orElse(null);
    }

    private Snapshot loadAndRepair() {
        Snapshot snap = readFile();
        boolean repaired = false;

        List<String> wsIds = workspaceStore.list().stream()
                .map(com.supertrader.demo.workspace.Workspace::id)
                .toList();

        // Drop members whose workspace no longer exists (workspaces are never
        // deletable, so this only happens after a manually corrupted store).
        List<TeamMember> kept = new ArrayList<>();
        for (TeamMember m : snap.members()) {
            if (wsIds.contains(m.workspaceId())) kept.add(m);
            else { log.warn("Dropping orphan member {} (workspace {} no longer exists)",
                    m.id(), m.workspaceId()); repaired = true; }
        }
        List<TeamMember> members = kept;

        // Invariant: EVERY workspace has at least one active OWNER. First access
        // to a workspace auto-creates its single initial OWNER (本地所有者).
        for (String wsId : wsIds) {
            boolean hasActiveOwner = members.stream().anyMatch(m ->
                    m.workspaceId().equals(wsId)
                            && ROLE_OWNER.equals(m.role()) && m.active());
            if (!hasActiveOwner) {
                String now = Instant.now().toString();
                members = new ArrayList<>(members);
                members.add(new TeamMember(UUID.randomUUID().toString(), wsId,
                        DEFAULT_OWNER_NAME, ROLE_OWNER, now, now, true));
                repaired = true;
            }
        }

        // Invariant: each workspace's current actor must resolve to an ACTIVE
        // member of that workspace; otherwise fall back to its first active OWNER.
        Map<String, String> actors = new HashMap<>(snap.currentActors());
        for (String wsId : wsIds) {
            TeamMember resolved = byId(new Snapshot(members, actors), wsId, actors.get(wsId));
            if (resolved == null || !resolved.active()) {
                actors.put(wsId, firstActiveOwner(members, wsId).id());
                repaired = true;
            }
        }

        if (repaired) {
            // Repair is persisted so the next request (and every restart) sees a
            // consistent, healthy file. A corrupt file is rebuilt from scratch.
            persist(new Snapshot(members, actors));
        }
        return new Snapshot(members, actors);
    }

    /** Read the file; any problem -> fresh empty snapshot (repair path). */
    private Snapshot readFile() {
        if (!Files.exists(file)) return new Snapshot(List.of(), Map.of());
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return new Snapshot(List.of(), Map.of());
            JsonNode root = mapper.readTree(bytes);
            JsonNode arr = root.path("members");
            if (!arr.isArray()) return new Snapshot(List.of(), Map.of());
            List<TeamMember> out = new ArrayList<>();
            for (JsonNode n : arr) {
                try {
                    TeamMember m = mapper.treeToValue(n, TeamMember.class);
                    if (isValid(m)) out.add(m);
                    else log.warn("Dropping one unreadable team entry from {}: {}", file, n);
                } catch (Exception skip) {
                    log.warn("Dropping one unreadable team entry from {}: {}", file, skip.toString());
                }
            }
            Map<String, String> actors = new HashMap<>();
            JsonNode a = root.path("currentActors");
            if (a.isObject()) {
                a.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && e.getValue().isTextual()) {
                        actors.put(e.getKey(), e.getValue().asText());
                    }
                });
            }
            return new Snapshot(out, actors);
        } catch (Exception e) {
            // Corrupt / unreadable file: safe-degrade to the repair path, never
            // fail startup or any request.
            log.warn("Team file unreadable (will repair): {} — {}", file, e.toString());
            return new Snapshot(List.of(), Map.of());
        }
    }

    private static boolean isValid(TeamMember m) {
        return m != null
                && m.id() != null && !m.id().isBlank()
                && m.workspaceId() != null && !m.workspaceId().isBlank()
                && m.displayName() != null && !m.displayName().isBlank()
                && m.role() != null && ROLES.contains(m.role())
                && m.createdAt() != null && !m.createdAt().isBlank()
                && m.updatedAt() != null && !m.updatedAt().isBlank();
    }

    /** Atomic-ish write: temp file then move (fallback to non-atomic move). */
    private void persist(Snapshot snap) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), new TeamsFile(SCHEMA, snap.members(), snap.currentActors()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Persistence failure must never break a request beyond a clear
            // error. The in-memory snapshot remains valid for this request.
            throw new TeamApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    TeamApiException.CODE_PERSIST_FAILED,
                    "团队成员保存失败：" + e.getMessage());
        }
    }

    /** The on-disk envelope. */
    record TeamsFile(
            @JsonProperty("schema") String schema,
            @JsonProperty("members") List<TeamMember> members,
            @JsonProperty("currentActors") Map<String, String> currentActors) {
        @JsonCreator
        TeamsFile {}
    }
}
