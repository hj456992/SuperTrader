package com.supertrader.demo.workspace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.UUID;

/**
 * Local, disk-persisted store of workspace NON-SENSITIVE metadata (Module 5).
 *
 * Contract:
 *  - Persists {@code {id, name, createdAt, updatedAt}} only — never
 *    credentials, tokens, private keys or accounts.
 *  - Persists to a single JSON file whose default location is
 *    {@code ../.run/workspaces.json} relative to the backend working dir
 *    (backend/), i.e. {@code rebuild/.run/workspaces.json} — the ONLY allowed
 *    location, and it is Git-ignored (rebuild/.gitignore covers .run/).
 *  - The CURRENT workspace id is persisted in the same file, so the user's
 *    selection survives backend restarts.
 *  - SAFE RECOVERY: a missing / empty / corrupt file, or a file whose workspace
 *    list is empty / unreadable, never fails startup or any request — the store
 *    repairs it by (re)creating the default workspace and persisting it. The
 *    "at least one workspace" invariant is enforced on load AND on every
 *    mutation. There is NO delete operation anywhere: the API offers no way to
 *    remove a workspace.
 *  - No trading meaning: this store has no account/order/cancel state and
 *    performs NO SimNow interaction of any kind.
 *
 * Concurrency: all mutating/reading entry points are {@code synchronized}; the
 * file is read on every operation and written atomically (tmp + move).
 */
@Component
public class WorkspaceStore {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceStore.class);

    static final String SCHEMA = "workspaces.v1";
    static final int MAX_NAME_LENGTH = 40;
    static final String DEFAULT_WORKSPACE_NAME = "默认工作空间";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public WorkspaceStore(@Value("${app.workspaces.file}") String workspacesFile) {
        this.file = Path.of(workspacesFile);
    }

    /** Visible for tests: the resolved store file path. */
    Path filePath() {
        return file;
    }

    // ------------------------------------------------------------------ //
    // Read API
    // ------------------------------------------------------------------ //

    /** All workspaces, oldest first (creation order). Never empty. */
    public synchronized List<Workspace> list() {
        return snapshot().workspaces();
    }

    /** Id of the currently selected workspace (always a member of the list). */
    public synchronized String currentWorkspaceId() {
        return snapshot().currentWorkspaceId();
    }

    /** The currently selected workspace. Never null. */
    public synchronized Workspace current() {
        return byId(snapshot(), snapshot().currentWorkspaceId());
    }

    // ------------------------------------------------------------------ //
    // Write API (NO delete exists by design — at least one workspace always)
    // ------------------------------------------------------------------ //

    /** Create a workspace with a unique id and server-validated name. */
    public synchronized Workspace create(String rawName) {
        String name = validateName(rawName);
        Snapshot snap = snapshot();
        ensureNameFree(snap, name, null);
        String now = Instant.now().toString();
        Workspace ws = new Workspace(UUID.randomUUID().toString(), name, now, now);
        List<Workspace> next = new ArrayList<>(snap.workspaces());
        next.add(ws);
        persist(new Snapshot(snap.currentWorkspaceId(), next));
        return ws;
    }

    /** Rename a workspace (any id; the page offers it for the current one). */
    public synchronized Workspace rename(String id, String rawName) {
        String name = validateName(rawName);
        Snapshot snap = snapshot();
        Workspace target = byIdOrThrow(snap, id);
        ensureNameFree(snap, name, id);
        String now = Instant.now().toString();
        Workspace renamed = new Workspace(target.id(), name, target.createdAt(), now);
        List<Workspace> next = new ArrayList<>(snap.workspaces());
        next.replaceAll(w -> w.id().equals(id) ? renamed : w);
        persist(new Snapshot(snap.currentWorkspaceId(), next));
        return renamed;
    }

    /** Switch the current selection. */
    public synchronized Workspace switchTo(String id) {
        if (id == null || id.isBlank()) {
            throw WorkspaceApiException.invalidId("切换请求必须包含非空的 id");
        }
        Snapshot snap = snapshot();
        byIdOrThrow(snap, id); // 404 when unknown
        persist(new Snapshot(id, snap.workspaces()));
        return byId(snap, id);
    }

    /**
     * Ensure a workspace with a SPECIFIC id exists, then make it the current
     * selection. Used by the Agent Demo to register its server-authoritative
     * fixed workspace ({@code ws-agent-demo}) so that the Demo's
     * ConversationSession / HarnessTurnRequest / ToolCall / ToolProxy all share
     * the SAME workspace id — without disabling the ToolProxy workspace check.
     * This is NOT a client-facing API; it is invoked once at Demo startup from
     * server-owned configuration.
     */
    public synchronized Workspace ensureWorkspaceAndSelect(String id, String name) {
        if (id == null || id.isBlank()) {
            throw WorkspaceApiException.invalidId("workspace id 不能为空");
        }
        Snapshot snap = snapshot();
        String now = Instant.now().toString();
        Workspace found = null;
        List<Workspace> next = new ArrayList<>(snap.workspaces());
        for (Workspace w : next) {
            if (w.id().equals(id)) {
                found = w;
                break;
            }
        }
        if (found == null) {
            found = new Workspace(id, name == null ? id : name, now, now);
            next.add(found);
        }
        persist(new Snapshot(id, next));
        return found;
    }

    // ------------------------------------------------------------------ //
    // Validation (server-side, applied to EVERY input)
    // ------------------------------------------------------------------ //

    /**
     * Validate a workspace name: required, trimmed, 1..{@value #MAX_NAME_LENGTH}
     * chars, no control characters. Returns the trimmed name or throws
     * {@link WorkspaceApiException#invalidName}.
     */
    static String validateName(String rawName) {
        if (rawName == null) {
            throw WorkspaceApiException.invalidName("工作空间名称不能为空");
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            throw WorkspaceApiException.invalidName("工作空间名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw WorkspaceApiException.invalidName(
                    "工作空间名称过长（最多 " + MAX_NAME_LENGTH + " 个字符）");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw WorkspaceApiException.invalidName("工作空间名称包含不允许的控制字符");
            }
        }
        return name;
    }

    /** Duplicate = same trimmed name (ASCII case-insensitive), excluding self. */
    private static void ensureNameFree(Snapshot snap, String name, String selfId) {
        for (Workspace w : snap.workspaces()) {
            if (!w.id().equals(selfId) && w.name().equalsIgnoreCase(name)) {
                throw WorkspaceApiException.duplicateName("已存在同名工作空间：" + w.name());
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Snapshot load + repair (missing / empty / corrupt -> safe default)
    // ------------------------------------------------------------------ //

    private record Snapshot(String currentWorkspaceId, List<Workspace> workspaces) {}

    private Workspace byId(Snapshot snap, String id) {
        for (Workspace w : snap.workspaces()) {
            if (w.id().equals(id)) return w;
        }
        // Invariant: currentWorkspaceId always resolves after repair. Defensive
        // fallback so no request can ever 5xx.
        return snap.workspaces().get(0);
    }

    private Workspace byIdOrThrow(Snapshot snap, String id) {
        for (Workspace w : snap.workspaces()) {
            if (w.id().equals(id)) return w;
        }
        throw WorkspaceApiException.notFound("工作空间不存在：" + id);
    }

    private Snapshot snapshot() {
        return loadAndRepair();
    }

    private Snapshot loadAndRepair() {
        Snapshot snap = readFile();
        boolean repaired = false;

        // Invariant: at least one workspace ALWAYS exists. A missing/empty/
        // corrupt file, or an empty list, is repaired with the default one.
        if (snap.workspaces().isEmpty()) {
            String now = Instant.now().toString();
            snap = new Snapshot(Workspace.DEFAULT_ID,
                    List.of(new Workspace(Workspace.DEFAULT_ID, DEFAULT_WORKSPACE_NAME, now, now)));
            repaired = true;
        }
        // Invariant: currentWorkspaceId must resolve; otherwise fall back to the
        // first (oldest) workspace.
        String current = snap.currentWorkspaceId();
        boolean currentKnown = false;
        for (Workspace w : snap.workspaces()) {
            if (w.id().equals(current)) { currentKnown = true; break; }
        }
        if (!currentKnown) {
            snap = new Snapshot(snap.workspaces().get(0).id(), snap.workspaces());
            repaired = true;
        }
        if (repaired) {
            // Repair is persisted so the next request (and every restart) sees a
            // consistent, healthy file. A corrupt file is rebuilt from scratch.
            persist(snap);
        }
        return snap;
    }

    /** Read the file; any problem -> fresh empty snapshot (repair path). */
    private Snapshot readFile() {
        if (!Files.exists(file)) return new Snapshot(null, List.of());
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return new Snapshot(null, List.of());
            JsonNode root = mapper.readTree(bytes);
            JsonNode arr = root.path("workspaces");
            if (!arr.isArray()) return new Snapshot(null, List.of());
            List<Workspace> out = new ArrayList<>();
            for (JsonNode n : arr) {
                try {
                    Workspace w = mapper.treeToValue(n, Workspace.class);
                    if (isValid(w)) out.add(w);
                    else log.warn("Dropping one unreadable workspace entry from {}: {}",
                            file, n);
                } catch (Exception skip) {
                    log.warn("Dropping one unreadable workspace entry from {}: {}",
                            file, skip.toString());
                }
            }
            out.sort(Comparator.comparing(Workspace::createdAt));
            String current = root.path("currentWorkspaceId").isTextual()
                    ? root.path("currentWorkspaceId").asText() : null;
            return new Snapshot(current, out);
        } catch (Exception e) {
            // Corrupt / unreadable file: safe-degrade to the repair path, never
            // fail startup or any request.
            log.warn("Workspace file unreadable (will repair with default): {} — {}",
                    file, e.toString());
            return new Snapshot(null, List.of());
        }
    }

    private static boolean isValid(Workspace w) {
        return w != null
                && w.id() != null && !w.id().isBlank()
                && w.name() != null && !w.name().isBlank()
                && w.createdAt() != null && !w.createdAt().isBlank()
                && w.updatedAt() != null && !w.updatedAt().isBlank();
    }

    /** Atomic-ish write: temp file then move (fallback to non-atomic move). */
    private void persist(Snapshot snap) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(),
                    new WorkspacesFile(SCHEMA, snap.currentWorkspaceId(), snap.workspaces()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Persistence failure must never break a request beyond a clear
            // error. The in-memory snapshot remains valid for this request.
            throw new WorkspaceApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "WORKSPACE_PERSIST_FAILED", "工作空间保存失败：" + e.getMessage());
        }
    }

    /** The on-disk envelope. */
    record WorkspacesFile(
            @JsonProperty("schema") String schema,
            @JsonProperty("currentWorkspaceId") String currentWorkspaceId,
            @JsonProperty("workspaces") List<Workspace> workspaces) {
        @JsonCreator
        WorkspacesFile {}
    }
}
