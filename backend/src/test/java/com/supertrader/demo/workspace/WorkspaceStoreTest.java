package com.supertrader.demo.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Unit tests for {@link WorkspaceStore} — local workspace metadata CRUD,
 * server-side validation, restart persistence and safe recovery from a
 * missing / empty / corrupt store file. All tests run against a scratch temp
 * file; the real rebuild/.run/ is never touched.
 */
class WorkspaceStoreTest {

    @TempDir
    Path tempDir;

    private Path file;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("workspaces.json");
    }

    private WorkspaceStore store() {
        return new WorkspaceStore(file.toString());
    }

    // ------------------------------------------------------------------ //
    // Default workspace + at-least-one invariant
    // ------------------------------------------------------------------ //

    @Test
    void missingFileRepairsToDefaultWorkspace() {
        WorkspaceStore store = store();
        List<Workspace> list = store.list();
        assertEquals(1, list.size());
        Workspace def = list.get(0);
        assertEquals(Workspace.DEFAULT_ID, def.id());
        assertEquals(WorkspaceStore.DEFAULT_WORKSPACE_NAME, def.name());
        assertNotNull(def.createdAt());
        assertNotNull(def.updatedAt());
        // The repair is persisted, so the file now exists with the default.
        assertTrue(Files.exists(file));
        assertEquals(Workspace.DEFAULT_ID, store.currentWorkspaceId());
    }

    @Test
    void emptyFileRepairsToDefaultWorkspace() throws Exception {
        Files.writeString(file, "");
        WorkspaceStore store = store();
        assertEquals(1, store.list().size());
        assertEquals(Workspace.DEFAULT_ID, store.currentWorkspaceId());
    }

    @Test
    void corruptFileRepairsToDefaultWorkspace() throws Exception {
        Files.writeString(file, "{{{ definitely not json \n 123");
        WorkspaceStore store = store();
        assertEquals(1, store.list().size());
        assertEquals(Workspace.DEFAULT_ID, store.list().get(0).id());
        assertEquals(Workspace.DEFAULT_ID, store.currentWorkspaceId());
        // File was rebuilt healthy.
        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertEquals("workspaces.v1", root.path("schema").asText());
        assertTrue(root.path("workspaces").isArray());
    }

    @Test
    void emptyWorkspaceListRepairsToDefaultWorkspace() throws Exception {
        Files.writeString(file, "{\"schema\":\"workspaces.v1\",\"currentWorkspaceId\":null,\"workspaces\":[]}");
        WorkspaceStore store = store();
        assertEquals(1, store.list().size());
        assertEquals(Workspace.DEFAULT_ID, store.currentWorkspaceId());
    }

    @Test
    void invalidCurrentIdFallsBackToOldestWorkspace() throws Exception {
        WorkspaceStore store = store();
        store.create("A");
        store.create("B");
        // Corrupt ONLY the current selection in an otherwise valid file.
        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        String json = root.toString().replace("\"currentWorkspaceId\":\"" + root.path("currentWorkspaceId").asText() + "\"",
                "\"currentWorkspaceId\":\"no-such-id\"");
        Files.writeString(file, json);

        WorkspaceStore reopened = store();
        assertEquals(Workspace.DEFAULT_ID, reopened.currentWorkspaceId());
        assertEquals(3, reopened.list().size());
    }

    @Test
    void unreadableEntriesAreDroppedButRemainingOnesKept() throws Exception {
        // A hand-crafted file: one VALID workspace plus one invalid entry
        // (missing name). The store must drop the invalid one, keep the valid
        // one, and never crash.
        String json = "{\"schema\":\"workspaces.v1\","
                + "\"currentWorkspaceId\":\"w-valid\","
                + "\"workspaces\":["
                + "{\"id\":\"w-broken\",\"name\":null},"
                + "{\"id\":\"w-valid\",\"name\":\"保留\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}"
                + "]}";
        Files.writeString(file, json);
        WorkspaceStore reopened = store();
        assertTrue(reopened.list().stream().noneMatch(w -> "w-broken".equals(w.id())),
                "invalid entry must be dropped");
        assertEquals("w-valid", reopened.currentWorkspaceId());
        assertEquals(1, reopened.list().size());
        assertEquals("保留", reopened.list().get(0).name());
    }

    // ------------------------------------------------------------------ //
    // CRUD + validation
    // ------------------------------------------------------------------ //

    @Test
    void createAddsWorkspaceWithServerSideValidation() {
        WorkspaceStore store = store();
        Workspace ws = store.create("  策略实验室  ");
        // Name is trimmed by the server.
        assertEquals("策略实验室", ws.name());
        assertEquals(2, store.list().size());
        assertEquals(ws.createdAt(), ws.updatedAt());
        assertNotEquals(Workspace.DEFAULT_ID, ws.id());
    }

    @Test
    void blankNameRejected() {
        WorkspaceStore store = store();
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class, () -> store.create("   "));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals(WorkspaceApiException.CODE_INVALID_NAME, e.code());
    }

    @Test
    void nullNameRejected() {
        WorkspaceStore store = store();
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class, () -> store.create(null));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
    }

    @Test
    void overlongNameRejected() {
        WorkspaceStore store = store();
        String tooLong = "a".repeat(WorkspaceStore.MAX_NAME_LENGTH + 1);
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class, () -> store.create(tooLong));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals(WorkspaceApiException.CODE_INVALID_NAME, e.code());
    }

    @Test
    void maxLengthNameAccepted() {
        WorkspaceStore store = store();
        String ok = "a".repeat(WorkspaceStore.MAX_NAME_LENGTH);
        assertEquals(ok, store.create(ok).name());
    }

    @Test
    void controlCharactersRejected() {
        WorkspaceStore store = store();
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class,
                () -> store.create("bad\u0007name"));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
    }

    @Test
    void duplicateNameRejectedCaseInsensitive() {
        WorkspaceStore store = store();
        store.create("Alpha");
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class,
                () -> store.create("alpha"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(WorkspaceApiException.CODE_DUPLICATE_NAME, e.code());
        assertEquals(2, store.list().size(), "duplicate must not add a workspace");
    }

    @Test
    void duplicateNameRejectedExactChinese() {
        WorkspaceStore store = store();
        store.create("策略实验室");
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class,
                () -> store.create("策略实验室"));
        assertEquals(HttpStatus.CONFLICT, e.status());
    }

    @Test
    void renamingToOwnNameIsAllowed() {
        WorkspaceStore store = store();
        Workspace ws = store.create("X");
        Workspace renamed = store.rename(ws.id(), "X");
        assertEquals("X", renamed.name());
    }

    @Test
    void renameValidatesNameAndUniqueness() {
        WorkspaceStore store = store();
        Workspace ws = store.create("A");
        assertThrows(WorkspaceApiException.class, () -> store.rename(ws.id(), "   "));
        Workspace other = store.create("B");
        WorkspaceApiException dup = assertThrows(WorkspaceApiException.class,
                () -> store.rename(ws.id(), "b"));
        assertEquals(HttpStatus.CONFLICT, dup.status());
        assertNotNull(other);
    }

    @Test
    void renameUpdatesUpdatedAtOnly() {
        WorkspaceStore store = store();
        Workspace ws = store.create("旧名称");
        Workspace renamed = store.rename(ws.id(), "新名称");
        assertEquals("新名称", renamed.name());
        assertEquals(ws.createdAt(), renamed.createdAt(), "createdAt must never change");
        assertNotEquals(ws.updatedAt(), renamed.updatedAt(), "updatedAt must advance");
    }

    @Test
    void renameUnknownIdThrows404() {
        WorkspaceStore store = store();
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class,
                () -> store.rename("no-such", "x"));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals(WorkspaceApiException.CODE_WORKSPACE_NOT_FOUND, e.code());
    }

    @Test
    void switchToMovesCurrentSelection() {
        WorkspaceStore store = store();
        Workspace ws = store.create("策略实验室");
        assertEquals(Workspace.DEFAULT_ID, store.currentWorkspaceId());
        Workspace switched = store.switchTo(ws.id());
        assertEquals(ws.id(), switched.id());
        assertEquals(ws.id(), store.currentWorkspaceId());
    }

    @Test
    void switchToUnknownIdThrows404() {
        WorkspaceStore store = store();
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class,
                () -> store.switchTo("no-such"));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
    }

    @Test
    void switchToBlankIdThrows400() {
        WorkspaceStore store = store();
        WorkspaceApiException e = assertThrows(WorkspaceApiException.class,
                () -> store.switchTo("  "));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
        assertEquals(WorkspaceApiException.CODE_INVALID_ID, e.code());
    }

    // ------------------------------------------------------------------ //
    // Restart persistence
    // ------------------------------------------------------------------ //

    @Test
    void dataAndCurrentSelectionSurviveRestart() {
        WorkspaceStore first = store();
        Workspace a = first.create("策略实验室");
        Workspace b = first.create("量化回测");
        first.switchTo(b.id());

        // "Restart": a brand-new store over the same file.
        WorkspaceStore second = store();
        List<Workspace> list = second.list();
        assertEquals(3, list.size());
        assertEquals(b.id(), second.currentWorkspaceId(), "current selection must survive restart");
        assertEquals("量化回测", second.current().name());
        Workspace renamed = second.rename(a.id(), "策略研究");
        assertEquals("策略研究", renamed.name());

        // And another restart still sees the rename.
        WorkspaceStore third = store();
        assertEquals("策略研究",
                third.list().stream().filter(w -> w.id().equals(a.id())).findFirst().orElseThrow().name());
    }

    @Test
    void defaultWorkspaceSurvivesRestart() {
        WorkspaceStore first = store();
        assertEquals(Workspace.DEFAULT_ID, first.currentWorkspaceId());
        WorkspaceStore second = store();
        assertEquals(Workspace.DEFAULT_ID, second.currentWorkspaceId());
        assertEquals(1, second.list().size());
    }

    // ------------------------------------------------------------------ //
    // DTO shape: NON-SENSITIVE metadata only
    // ------------------------------------------------------------------ //

    @Test
    void serializedWorkspaceHasOnlyAllowedFields() throws Exception {
        WorkspaceStore store = store();
        Workspace ws = store.create("策略实验室");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(ws));
        Set<String> keys = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("id", "name", "createdAt", "updatedAt"), keys,
                "workspace JSON must carry ONLY non-sensitive metadata");
    }

    @Test
    void storeFileNeverContainsCredentialShapedContent() throws Exception {
        WorkspaceStore store = store();
        store.create("策略实验室");
        String raw = Files.readString(file);
        assertFalse(raw.contains("password"), "store file must never contain password-shaped content");
        assertFalse(raw.contains("authCode"));
        assertFalse(raw.contains("appId"));
        assertFalse(raw.contains("token"));
        assertFalse(raw.contains("secret"));
    }
}
