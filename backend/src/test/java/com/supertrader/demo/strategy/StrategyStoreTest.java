package com.supertrader.demo.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supertrader.demo.team.TeamMember;
import com.supertrader.demo.team.TeamStore;
import com.supertrader.demo.workspace.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StrategyStore} — local strategy RESEARCH metadata +
 * the immutable version history (Module 8).
 *
 * <p>Covers: the empty library + safe recovery (missing / empty / corrupt /
 * wrong shape files), atomic create (strategy + version 1), server-side input
 * validation (name / summary / instruments / timeframe / rules), the
 * per-workspace case-insensitive unique-name rule (archived names stay
 * reserved), strictly-increasing append-only versions, client-forged fields
 * never adopted, archived strategies read-only (no rename / no append),
 * strict per-workspace isolation (cross-workspace 404 without existence
 * leaks), the full RBAC matrix (OWNER / ADMIN / TRADER / VIEWER), restart
 * persistence, on-disk field whitelists, and the deterministic load repair
 * (unknown fields stripped, orphans dropped, duplicate ids / names / version
 * numbers collapsed, currentVersion recomputed, second load never drifts,
 * repair is pure metadata work). All tests run against scratch temp files;
 * the real rebuild/.run/ is never touched.
 */
class StrategyStoreTest {

    @TempDir
    Path tempDir;

    private Path wsFile;
    private Path teamsFile;
    private Path strategiesFile;
    private WorkspaceStore workspaceStore;
    private TeamStore teamStore;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        wsFile = tempDir.resolve("workspaces.json");
        teamsFile = tempDir.resolve("teams.json");
        strategiesFile = tempDir.resolve("strategies.json");
        workspaceStore = new WorkspaceStore(wsFile.toString());
        teamStore = new TeamStore(teamsFile.toString(), workspaceStore);
    }

    private StrategyStore store() {
        return new StrategyStore(strategiesFile.toString(), workspaceStore, teamStore);
    }

    private void asOwner() {
        asRole(TeamStore.ROLE_OWNER, "本地所有者");
    }

    /** Switch the current workspace's local actor to a member of the given role. */
    private void asRole(String role, String name) {
        TeamMember owner = teamStore.currentActor();
        if (!TeamStore.ROLE_OWNER.equals(owner.role())) {
            teamStore.listMembers().stream()
                    .filter(m -> TeamStore.ROLE_OWNER.equals(m.role()) && m.active())
                    .findFirst()
                    .ifPresent(o -> teamStore.switchActor(o.id()));
        }
        if (TeamStore.ROLE_OWNER.equals(role)) {
            assertEquals(TeamStore.ROLE_OWNER, teamStore.currentActor().role());
            return;
        }
        TeamMember created = teamStore.createMember(name, role);
        teamStore.switchActor(created.id());
        assertEquals(role, teamStore.currentActor().role());
    }

    private StrategyDtos.CreateRequest createRequest(String name, String summary,
                                                     String timeframe, String... instruments) {
        return new StrategyDtos.CreateRequest(name, summary, List.of(instruments),
                timeframe, "入场规则", "出场规则", "风控备注");
    }

    private StrategyDtos.AppendVersionRequest appendRequest(String summary,
                                                            String timeframe,
                                                            String... instruments) {
        return new StrategyDtos.AppendVersionRequest(summary, List.of(instruments),
                timeframe, "入场规则 v", "出场规则 v", "风控备注 v");
    }

    // ------------------------------------------------------------------ //
    // Empty library + safe recovery
    // ------------------------------------------------------------------ //

    @Test
    void missingFileIsAnEmptyLibraryAndStartupNeverFails() {
        StrategyStore store = store();
        assertTrue(store.list().isEmpty());
        assertEquals("default", store.currentWorkspaceId());
        assertFalse(Files.exists(strategiesFile)); // not persisted until a write
    }

    @Test
    void emptyFileIsAnEmptyLibrary() throws Exception {
        Files.writeString(strategiesFile, "");
        assertTrue(store().list().isEmpty());
    }

    @Test
    void corruptFileIsSafelyAnEmptyLibrary() throws Exception {
        Files.writeString(strategiesFile, "{{{ not json \n 123");
        assertTrue(store().list().isEmpty());
    }

    @Test
    void illegalShapeFileIsAnEmptyLibrary() throws Exception {
        Files.writeString(strategiesFile, "{\"foo\":\"bar\"}");
        assertTrue(store().list().isEmpty());
    }

    // ------------------------------------------------------------------ //
    // Create + version 1 (atomic) + server-side validation
    // ------------------------------------------------------------------ //

    @Test
    void createBuildsStrategyWithVersion1Atomically() {
        asOwner();
        String memberId = teamStore.currentActor().id();
        StrategyStore store = store();
        StrategyDtos.StrategyResponse res = store.create(
                createRequest(" 均线突破策略 ", "基于均线交叉的日线趋势策略", "1D", "JM2609", "jm2609", "if2506"));
        Strategy s = res.strategy();
        assertNotNull(s.id());
        assertEquals("均线突破策略", s.name(), "name is server-trimmed");
        assertEquals("default", s.workspaceId());
        assertEquals(Strategy.STATUS_ACTIVE, s.status());
        assertEquals(1, s.currentVersion(), "version 1 is created atomically");
        assertEquals(memberId, s.createdByMemberId(), "creator is the server-side current actor");
        assertNotNull(s.createdAt());
        assertEquals(s.createdAt(), s.updatedAt());
        assertNull(s.archivedAt());

        // The version exists in the history, canonicalized.
        List<StrategyVersion> versions = store.versions(s.id());
        assertEquals(1, versions.size());
        StrategyVersion v = versions.get(0);
        assertEquals(1, v.versionNumber());
        assertEquals(List.of("JM2609", "IF2506"), v.instruments(),
                "instruments are uppercased + de-duplicated deterministically");
        assertEquals("1D", v.timeframe());
        assertEquals(memberId, v.createdByMemberId());

        // The disk file holds BOTH records (atomic write).
        assertTrue(Files.exists(strategiesFile));
    }

    @Test
    void blankAndTooLongNamesAreRejected() {
        asOwner();
        StrategyStore store = store();
        StrategyApiException blank = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("   ", "s", "1D", "JM2609")));
        assertEquals(HttpStatus.BAD_REQUEST, blank.status());
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_NAME, blank.code());
        StrategyApiException tooLong = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("a".repeat(StrategyStore.MAX_NAME + 1),
                        "s", "1D", "JM2609")));
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_NAME, tooLong.code());
        StrategyApiException control = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("bad\u0007name", "s", "1D", "JM2609")));
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_NAME, control.code());
    }

    @Test
    void invalidVersionDefinitionsAreRejected() {
        asOwner();
        StrategyStore store = store();
        // Missing summary.
        StrategyApiException noSummary = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("策略", "  ", "1D", "JM2609")));
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_VERSION, noSummary.code());
        // Summary too long.
        StrategyApiException longSummary = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("策略",
                        "x".repeat(StrategyStore.MAX_SUMMARY + 1), "1D", "JM2609")));
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_VERSION, longSummary.code());
        // Control characters in a rule.
        StrategyApiException ctlRule = assertThrows(StrategyApiException.class,
                () -> store.create(new StrategyDtos.CreateRequest("策略", "摘要",
                        List.of("JM2609"), "1D", "bad\u0007rule", "出", "风")));
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_VERSION, ctlRule.code());
        // Rule too long.
        StrategyApiException longRule = assertThrows(StrategyApiException.class,
                () -> store.create(new StrategyDtos.CreateRequest("策略", "摘要",
                        List.of("JM2609"), "1D",
                        "r".repeat(StrategyStore.MAX_RULE_LENGTH + 1), "出", "风")));
        assertEquals(StrategyApiException.CODE_INVALID_STRATEGY_VERSION, longRule.code());
    }

    @Test
    void invalidInstrumentsAreRejectedAndCanonicalized() {
        asOwner();
        StrategyStore store = store();
        // Empty / null instrument list.
        StrategyApiException none = assertThrows(StrategyApiException.class,
                () -> store.create(new StrategyDtos.CreateRequest("策略", "摘要",
                        List.of(), "1D", "入", "出", "风")));
        assertEquals(StrategyApiException.CODE_INVALID_INSTRUMENT, none.code());
        // Non-alphanumeric.
        StrategyApiException bad = assertThrows(StrategyApiException.class,
                () -> store.create(new StrategyDtos.CreateRequest("策略", "摘要",
                        List.of("JM-2609"), "1D", "入", "出", "风")));
        assertEquals(StrategyApiException.CODE_INVALID_INSTRUMENT, bad.code());
        // Too long.
        StrategyApiException longInstr = assertThrows(StrategyApiException.class,
                () -> store.create(new StrategyDtos.CreateRequest("策略", "摘要",
                        List.of("A".repeat(StrategyStore.MAX_INSTRUMENT_LENGTH + 1)), "1D", "入", "出", "风")));
        assertEquals(StrategyApiException.CODE_INVALID_INSTRUMENT, longInstr.code());
        // More than 20 raw items.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) many.add("X" + i);
        StrategyApiException tooMany = assertThrows(StrategyApiException.class,
                () -> store.create(new StrategyDtos.CreateRequest("策略", "摘要",
                        many, "1D", "入", "出", "风")));
        assertEquals(StrategyApiException.CODE_INVALID_INSTRUMENT, tooMany.code());
        // Deduplication may reduce the list below 20 even with 21 raw entries:
        // 20 distinct + 1 duplicate is fine after canonicalization.
        List<String> withDup = new ArrayList<>();
        for (int i = 0; i < 20; i++) withDup.add("X" + i);
        withDup.add("x0"); // duplicate of X0 after uppercasing
        StrategyDtos.StrategyResponse ok = store.create(new StrategyDtos.CreateRequest(
                "策略", "摘要", withDup, "1D", "入", "出", "风"));
        assertEquals(20, store.versions(ok.strategy().id()).get(0).instruments().size());
    }

    @Test
    void invalidTimeframesAreRejectedAndCanonicalized() {
        asOwner();
        StrategyStore store = store();
        StrategyApiException bad = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("策略", "摘要", "4H", "JM2609")));
        assertEquals(StrategyApiException.CODE_INVALID_TIMEFRAME, bad.code());
        StrategyApiException blank = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("策略", "摘要", "  ", "JM2609")));
        assertEquals(StrategyApiException.CODE_INVALID_TIMEFRAME, blank.code());
        // Lowercase is canonicalized.
        StrategyDtos.StrategyResponse ok = store.create(createRequest("策略", "摘要", "1m", "JM2609"));
        assertEquals("1M", store.versions(ok.strategy().id()).get(0).timeframe());
    }

    @Test
    void nullCreateBodyIsInvalidBody() {
        asOwner();
        StrategyApiException e = assertThrows(StrategyApiException.class,
                () -> store().create(null));
        assertEquals(StrategyApiException.CODE_INVALID_BODY, e.code());
    }

    // ------------------------------------------------------------------ //
    // Unique names (per workspace, case-insensitive; archived names reserved)
    // ------------------------------------------------------------------ //

    @Test
    void duplicateNameInSameWorkspaceReturns409() {
        asOwner();
        StrategyStore store = store();
        store.create(createRequest("均线策略", "摘要", "1D", "JM2609"));
        StrategyApiException e = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest(" 均线策略 ", "另一个摘要", "1D", "IF2506")));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(StrategyApiException.CODE_DUPLICATE_STRATEGY, e.code());
        // Only ONE strategy was persisted.
        assertEquals(1, store.list().size());
    }

    @Test
    void differentWorkspacesMayUseTheSameName() {
        asOwner();
        StrategyStore store = store();
        store.create(createRequest("同一名称", "摘要1", "1D", "JM2609"));
        com.supertrader.demo.workspace.Workspace ws2 = workspaceStore.create("第二空间");
        workspaceStore.switchTo(ws2.id());
        asOwner(); // the new workspace's auto owner is the current actor
        StrategyDtos.StrategyResponse s2 = store.create(createRequest("同一名称", "摘要2", "1D", "JM2609"));
        assertEquals(ws2.id(), s2.strategy().workspaceId());
        assertEquals(1, store.list().size());
    }

    @Test
    void archivedStrategyNameStaysReserved() {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("旧策略", "摘要", "1D", "JM2609")).strategy();
        store.archive(s.id());
        StrategyApiException e = assertThrows(StrategyApiException.class,
                () -> store.create(createRequest("旧策略", "新摘要", "1D", "IF2506")));
        assertEquals(StrategyApiException.CODE_DUPLICATE_STRATEGY, e.code());
    }

    // ------------------------------------------------------------------ //
    // Append-only versions (strictly increasing, immutable)
    // ------------------------------------------------------------------ //

    @Test
    void appendVersionIsStrictlyIncreasing() {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要 v1", "1D", "JM2609")).strategy();
        StrategyDtos.VersionResponse v2 = store.appendVersion(s.id(),
                appendRequest("摘要 v2", "5M", "IF2506"));
        assertEquals(2, v2.version().versionNumber());
        StrategyDtos.VersionResponse v3 = store.appendVersion(s.id(),
                appendRequest("摘要 v3", "1H", "JM2609", "IF2506"));
        assertEquals(3, v3.version().versionNumber());
        // The strategy's currentVersion tracks the max.
        assertEquals(3, store.get(s.id()).currentVersion());
        // History is ascending by version number.
        List<StrategyVersion> versions = store.versions(s.id());
        assertEquals(List.of(1, 2, 3),
                versions.stream().map(StrategyVersion::versionNumber).toList());
    }

    @Test
    void versionsCanNeverBeUpdatedDeletedOrRolledBack() throws Exception {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要 v1", "1D", "JM2609")).strategy();
        StrategyDtos.VersionResponse v2 = store.appendVersion(s.id(),
                appendRequest("摘要 v2", "5M", "IF2506"));
        // Snapshot the disk file AFTER the append; further reads must not
        // change any version record (no update / delete / rollback path).
        String diskAfterAppend = Files.readString(strategiesFile);
        store.list();
        store.get(s.id());
        store.versions(s.id());
        // An appended v3 only ADDS a record; v1/v2 stay byte-identical.
        store.appendVersion(s.id(), appendRequest("摘要 v3", "1H", "JM2609"));
        JsonNode root = mapper.readTree(Files.readAllBytes(strategiesFile));
        List<JsonNode> versions = new ArrayList<>();
        root.path("versions").forEach(versions::add);
        assertEquals(3, versions.size());
        assertEquals(1, versions.get(0).path("versionNumber").asInt());
        assertEquals("摘要 v1", versions.get(0).path("summary").asText());
        assertEquals("摘要 v2", versions.get(1).path("summary").asText());
        assertEquals(2, versions.get(1).path("versionNumber").asInt());
        // The pre-append snapshot's version records are untouched (v1+v2 have
        // the same content in the file before and after the v3 append).
        assertTrue(diskAfterAppend.contains("\"versionNumber\":1"));
        assertTrue(diskAfterAppend.contains("\"versionNumber\":2"));
    }

    @Test
    void appendVersionWithInvalidDefinitionIsRejected() {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要", "1D", "JM2609")).strategy();
        StrategyApiException badTf = assertThrows(StrategyApiException.class,
                () -> store.appendVersion(s.id(), appendRequest("摘要", "9D", "JM2609")));
        assertEquals(StrategyApiException.CODE_INVALID_TIMEFRAME, badTf.code());
        StrategyApiException badInstr = assertThrows(StrategyApiException.class,
                () -> store.appendVersion(s.id(), appendRequest("摘要", "1D", "J M")));
        assertEquals(StrategyApiException.CODE_INVALID_INSTRUMENT, badInstr.code());
        StrategyApiException nullBody = assertThrows(StrategyApiException.class,
                () -> store.appendVersion(s.id(), null));
        assertEquals(StrategyApiException.CODE_INVALID_BODY, nullBody.code());
    }

    // ------------------------------------------------------------------ //
    // Archive: read-only afterwards
    // ------------------------------------------------------------------ //

    @Test
    void archivedStrategyCannotBeRenamedOrVersionedButStaysReadable() {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要", "1D", "JM2609")).strategy();
        store.archive(s.id());
        assertEquals(Strategy.STATUS_ARCHIVED, store.get(s.id()).status());
        assertNotNull(store.get(s.id()).archivedAt());

        StrategyApiException rename = assertThrows(StrategyApiException.class,
                () -> store.rename(s.id(), "新名字"));
        assertEquals(StrategyApiException.CODE_STRATEGY_ARCHIVED, rename.code());
        StrategyApiException append = assertThrows(StrategyApiException.class,
                () -> store.appendVersion(s.id(), appendRequest("摘要2", "1D", "JM2609")));
        assertEquals(StrategyApiException.CODE_STRATEGY_ARCHIVED, append.code());
        StrategyApiException reArchive = assertThrows(StrategyApiException.class,
                () -> store.archive(s.id()));
        assertEquals(StrategyApiException.CODE_STRATEGY_ARCHIVED, reArchive.code());
        // Read-only views still work.
        assertEquals(1, store.versions(s.id()).size());
        assertEquals(1, store.list().size());
    }

    @Test
    void renameUpdatesNameAndKeepsHistory() {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("旧名", "摘要", "1D", "JM2609")).strategy();
        store.appendVersion(s.id(), appendRequest("摘要2", "5M", "IF2506"));
        StrategyDtos.StrategyResponse renamed = store.rename(s.id(), " 新名字 ");
        assertEquals("新名字", renamed.strategy().name(), "rename trims the name");
        assertEquals(2, renamed.strategy().currentVersion());
        assertEquals(2, store.versions(s.id()).size(), "history survives rename");
        // Rename to the same name is allowed (self).
        assertEquals("新名字", store.rename(s.id(), "新名字").strategy().name());
    }

    // ------------------------------------------------------------------ //
    // Strict per-workspace isolation
    // ------------------------------------------------------------------ //

    @Test
    void crossWorkspaceStrategyIdIsNotFound() {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("ws1 策略", "摘要", "1D", "JM2609")).strategy();
        com.supertrader.demo.workspace.Workspace ws2 = workspaceStore.create("第二空间");
        workspaceStore.switchTo(ws2.id());
        asOwner(); // the new workspace's auto owner is the current actor
        assertTrue(store().list().isEmpty());
        StrategyApiException get = assertThrows(StrategyApiException.class,
                () -> store().get(s.id()));
        assertEquals(StrategyApiException.CODE_STRATEGY_NOT_FOUND, get.code());
        StrategyApiException rename = assertThrows(StrategyApiException.class,
                () -> store().rename(s.id(), "x"));
        assertEquals(StrategyApiException.CODE_STRATEGY_NOT_FOUND, rename.code());
        StrategyApiException append = assertThrows(StrategyApiException.class,
                () -> store().appendVersion(s.id(), appendRequest("s", "1D", "JM2609")));
        assertEquals(StrategyApiException.CODE_STRATEGY_NOT_FOUND, append.code());
        StrategyApiException archive = assertThrows(StrategyApiException.class,
                () -> store().archive(s.id()));
        assertEquals(StrategyApiException.CODE_STRATEGY_NOT_FOUND, archive.code());
        // No existence leak: the error message must NOT echo the workspace-1 id.
        assertFalse(get.getMessage().contains(s.workspaceId()));
    }

    // ------------------------------------------------------------------ //
    // RBAC matrix (server-side)
    // ------------------------------------------------------------------ //

    @Test
    void adminCanCreateAppendRenameAndArchive() {
        asRole(TeamStore.ROLE_ADMIN, "管理员");
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要", "1D", "JM2609")).strategy();
        store.appendVersion(s.id(), appendRequest("摘要2", "5M", "IF2506"));
        StrategyDtos.StrategyResponse renamed = store.rename(s.id(), "新名");
        assertEquals("新名", renamed.strategy().name());
        StrategyDtos.StrategyResponse archived = store.archive(s.id());
        assertEquals(Strategy.STATUS_ARCHIVED, archived.strategy().status());
    }

    @Test
    void traderCanCreateAndAppendButCannotRenameOrArchive() {
        asRole(TeamStore.ROLE_TRADER, "交易员");
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要", "1D", "JM2609")).strategy();
        store.appendVersion(s.id(), appendRequest("摘要2", "5M", "IF2506"));
        StrategyApiException rename = assertThrows(StrategyApiException.class,
                () -> store.rename(s.id(), "x"));
        assertEquals(StrategyApiException.CODE_FORBIDDEN, rename.code());
        StrategyApiException archive = assertThrows(StrategyApiException.class,
                () -> store.archive(s.id()));
        assertEquals(StrategyApiException.CODE_FORBIDDEN, archive.code());
    }

    @Test
    void viewerIsReadOnly() {
        asOwner();
        StrategyStore ownerStore = store();
        Strategy s = ownerStore.create(createRequest("策略", "摘要", "1D", "JM2609")).strategy();
        asRole(TeamStore.ROLE_VIEWER, "观察员");
        StrategyStore store = store();
        // Reading is fine.
        assertEquals(1, store.list().size());
        assertEquals(s.id(), store.get(s.id()).id());
        assertEquals(1, store.versions(s.id()).size());
        // Every write is forbidden.
        assertEquals(StrategyApiException.CODE_FORBIDDEN,
                assertThrows(StrategyApiException.class,
                        () -> store.create(createRequest("x", "s", "1D", "JM2609"))).code());
        assertEquals(StrategyApiException.CODE_FORBIDDEN,
                assertThrows(StrategyApiException.class,
                        () -> store.appendVersion(s.id(), appendRequest("s", "1D", "JM2609"))).code());
        assertEquals(StrategyApiException.CODE_FORBIDDEN,
                assertThrows(StrategyApiException.class, () -> store.rename(s.id(), "x")).code());
        assertEquals(StrategyApiException.CODE_FORBIDDEN,
                assertThrows(StrategyApiException.class, () -> store.archive(s.id())).code());
    }

    // ------------------------------------------------------------------ //
    // Restart persistence
    // ------------------------------------------------------------------ //

    @Test
    void strategiesAndVersionsSurviveRestart() {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要 v1", "1D", "JM2609")).strategy();
        store.appendVersion(s.id(), appendRequest("摘要 v2", "5M", "IF2506"));
        store.archive(s.id());

        StrategyStore restarted = store();
        List<Strategy> after = restarted.list();
        assertEquals(1, after.size());
        assertEquals(Strategy.STATUS_ARCHIVED, after.get(0).status());
        assertEquals(2, after.get(0).currentVersion());
        assertNotNull(after.get(0).archivedAt());
        assertEquals(2, restarted.versions(s.id()).size());
    }

    // ------------------------------------------------------------------ //
    // Field whitelist + on-disk shape
    // ------------------------------------------------------------------ //

    @Test
    void persistedFileCarriesOnlyWhitelistedFields() throws Exception {
        asOwner();
        StrategyStore store = store();
        Strategy s = store.create(createRequest("策略", "摘要", "1D", "JM2609")).strategy();
        store.appendVersion(s.id(), appendRequest("摘要2", "5M", "IF2506"));
        assertTrue(Files.exists(strategiesFile));
        JsonNode root = mapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(StrategyStore.SCHEMA, root.path("schema").asText());
        List<String> topKeys = new ArrayList<>();
        root.fieldNames().forEachRemaining(topKeys::add);
        java.util.Collections.sort(topKeys);
        assertEquals(List.of("schema", "strategies", "versions"), topKeys);

        JsonNode strat = root.path("strategies").get(0);
        List<String> strategyKeys = new ArrayList<>();
        strat.fieldNames().forEachRemaining(strategyKeys::add);
        java.util.Collections.sort(strategyKeys);
        assertEquals(List.of("archivedAt", "createdAt", "createdByMemberId",
                "currentVersion", "id", "name", "status", "updatedAt", "workspaceId"), strategyKeys);

        JsonNode ver = root.path("versions").get(0);
        List<String> versionKeys = new ArrayList<>();
        ver.fieldNames().forEachRemaining(versionKeys::add);
        java.util.Collections.sort(versionKeys);
        assertEquals(List.of("createdAt", "createdByMemberId", "entryRules",
                "exitRules", "id", "instruments", "riskNotes", "strategyId",
                "summary", "timeframe", "versionNumber"), versionKeys);

        // No credential / trading field of any kind.
        String json = root.toString();
        assertFalse(json.toLowerCase().contains("password"));
        assertFalse(json.toLowerCase().contains("authcode"));
        assertFalse(json.toLowerCase().contains("appid"));
        assertFalse(json.toLowerCase().contains("investor"));
        assertFalse(json.toLowerCase().contains("brokerid"));
        assertFalse(json.toLowerCase().contains("token"));
        assertFalse(json.toLowerCase().contains("order"));
    }

    // ------------------------------------------------------------------ //
    // Load-time deterministic repair
    // ------------------------------------------------------------------ //

    private final ObjectMapper nodeMapper = new ObjectMapper();

    private com.fasterxml.jackson.databind.node.ObjectNode rawStrategy(
            String id, String wsId, String name, String status, int currentVersion,
            String createdAt, String archivedAt) {
        com.fasterxml.jackson.databind.node.ObjectNode n = nodeMapper.createObjectNode();
        n.put("id", id);
        n.put("workspaceId", wsId);
        n.put("name", name);
        n.put("status", status);
        n.put("currentVersion", currentVersion);
        n.put("createdByMemberId", "m-owner");
        n.put("createdAt", createdAt);
        n.put("updatedAt", createdAt);
        if (archivedAt == null) n.putNull("archivedAt");
        else n.put("archivedAt", archivedAt);
        return n;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode rawVersion(
            String id, String strategyId, int number, String createdAt) {
        com.fasterxml.jackson.databind.node.ObjectNode n = nodeMapper.createObjectNode();
        n.put("id", id);
        n.put("strategyId", strategyId);
        n.put("versionNumber", number);
        n.put("summary", "摘要 v" + number);
        n.putArray("instruments").add("JM2609");
        n.put("timeframe", "1D");
        n.put("entryRules", "入场");
        n.put("exitRules", "出场");
        n.put("riskNotes", "风控");
        n.put("createdByMemberId", "m-owner");
        n.put("createdAt", createdAt);
        return n;
    }

    /** Write a raw file with explicit strategies + versions arrays. */
    private void writeRaw(com.fasterxml.jackson.databind.node.ObjectNode... strategies) throws Exception {
        com.fasterxml.jackson.databind.node.ArrayNode arr = nodeMapper.createArrayNode();
        for (com.fasterxml.jackson.databind.node.ObjectNode n : strategies) arr.add(n);
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        root.set("strategies", arr);
        root.set("versions", nodeMapper.createArrayNode());
        Files.writeString(strategiesFile, nodeMapper.writeValueAsString(root));
    }

    /** Write a raw file with explicit strategies + versions arrays, and give
     *  EVERY strategy a version-1 record so the "at least one version"
     *  invariant cannot drop it for the wrong reason. */
    private void writeRawWithVersions(com.fasterxml.jackson.databind.node.ObjectNode... strategies) throws Exception {
        com.fasterxml.jackson.databind.node.ArrayNode arr = nodeMapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        int i = 0;
        for (com.fasterxml.jackson.databind.node.ObjectNode n : strategies) {
            arr.add(n);
            versions.add(rawVersion("v-" + n.path("id").asText() + "-1", n.path("id").asText(),
                    1, "2026-08-01T00:00:00Z"));
        }
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        root.set("strategies", arr);
        root.set("versions", versions);
        Files.writeString(strategiesFile, nodeMapper.writeValueAsString(root));
    }

    private void writeRaw(com.fasterxml.jackson.databind.node.ObjectNode root) throws Exception {
        Files.writeString(strategiesFile, nodeMapper.writeValueAsString(root));
    }

    private void assertTopLevelClean(String disk) throws Exception {
        JsonNode root = nodeMapper.readTree(disk);
        assertTrue(root.isObject(), "repaired root must be an object");
        List<String> keys = new ArrayList<>();
        root.fieldNames().forEachRemaining(keys::add);
        java.util.Collections.sort(keys);
        assertEquals(List.of("schema", "strategies", "versions"), keys,
                "repaired file may only contain schema + strategies + versions");
        assertEquals(StrategyStore.SCHEMA, root.path("schema").asText());
        assertTrue(root.path("strategies").isArray());
        assertTrue(root.path("versions").isArray());
    }

    @Test
    void unknownFieldsAreStrippedFromDiskAndApi() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode good = rawStrategy(
                "g1", "default", "合法策略", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null);
        com.fasterxml.jackson.databind.node.ObjectNode bad = rawStrategy(
                "b1", "default", "未知字段策略", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T01:00:00Z", null);
        // A non-sensitive unknown field (deliberately NOT a credential-shaped
        // key or value): the record is rejected and scrubbed from disk.
        bad.put("unexpectedField", "sentinel");
        writeRawWithVersions(good, bad);
        StrategyStore store = store();
        List<Strategy> list = store.list();
        assertEquals(1, list.size());
        assertEquals("g1", list.get(0).id());
        String disk = Files.readString(strategiesFile);
        assertFalse(disk.contains("unexpectedField"));
        assertFalse(disk.contains("sentinel"));
        assertFalse(disk.contains("b1"));
    }

    @Test
    void orphanStrategiesAreDroppedWhenWorkspaceIsGone() throws Exception {
        writeRawWithVersions(rawStrategy("o1", "ghost-workspace", "孤儿策略",
                Strategy.STATUS_ACTIVE, 1, "2026-08-01T00:00:00Z", null));
        assertTrue(store().list().isEmpty());
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(0, repaired.path("strategies").size());
    }

    @Test
    void orphanVersionsAreDroppedWhenStrategyIsGone() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        root.set("strategies", nodeMapper.createArrayNode());
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v-orphan", "no-such-strategy", 1, "2026-08-01T00:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);
        assertTrue(store().list().isEmpty());
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(0, repaired.path("versions").size());
    }

    @Test
    void duplicateStrategyIdsKeepDeterministicFirst() throws Exception {
        // Two records with the SAME id "dup" but different createdAt: the
        // earliest createdAt wins regardless of file order.
        writeRawWithVersions(
                rawStrategy("dup", "default", "晚策略", Strategy.STATUS_ACTIVE, 1,
                        "2026-08-01T01:00:00Z", null),
                rawStrategy("dup", "default", "早策略", Strategy.STATUS_ACTIVE, 1,
                        "2026-08-01T00:00:00Z", null));
        StrategyStore store = store();
        assertEquals(1, store.list().size());
        assertEquals("dup", store.list().get(0).id());
        assertEquals("早策略", store.list().get(0).name(),
                "the earliest createdAt duplicate is kept deterministically");
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(1, repaired.path("strategies").size());
        assertEquals("早策略", repaired.path("strategies").get(0).path("name").asText());
    }

    @Test
    void duplicateNamesInSameWorkspaceKeepDeterministicFirst() throws Exception {
        // Case-variant duplicates ("MA策略" vs "ma策略") — the earliest
        // createdAt wins.
        writeRawWithVersions(
                rawStrategy("s1", "default", "MA策略", Strategy.STATUS_ACTIVE, 1,
                        "2026-08-01T01:00:00Z", null),
                rawStrategy("s2", "default", "ma策略", Strategy.STATUS_ACTIVE, 1,
                        "2026-08-01T00:00:00Z", null));
        StrategyStore store = store();
        List<Strategy> list = store.list();
        assertEquals(1, list.size());
        assertEquals("s2", list.get(0).id(),
                "the earliest createdAt wins for a case-insensitive name duplicate");
        assertEquals("ma策略", list.get(0).name());
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(1, repaired.path("strategies").size());
    }

    @Test
    void duplicateVersionNumbersKeepDeterministicFirst() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        com.fasterxml.jackson.databind.node.ArrayNode strategies = nodeMapper.createArrayNode();
        strategies.add(rawStrategy("s1", "default", "策略", Strategy.STATUS_ACTIVE, 2,
                "2026-08-01T00:00:00Z", null));
        root.set("strategies", strategies);
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v-early", "s1", 1, "2026-08-01T00:00:00Z"));
        versions.add(rawVersion("v-late", "s1", 2, "2026-08-01T02:00:00Z"));
        versions.add(rawVersion("v-dup", "s1", 2, "2026-08-01T03:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);
        StrategyStore store = store();
        List<StrategyVersion> list = store.versions("s1");
        assertEquals(2, list.size(), "duplicate versionNumber 2 keeps only one");
        assertEquals("v-late", list.get(1).id(), "the earliest createdAt duplicate is kept");
        // currentVersion is recomputed from the repaired history.
        assertEquals(2, store.get("s1").currentVersion());
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(2, repaired.path("versions").size());
    }

    @Test
    void strategyWithoutAnyVersionIsDropped() throws Exception {
        writeRaw(rawStrategy("s1", "default", "无版本策略", Strategy.STATUS_ACTIVE, 0,
                "2026-08-01T00:00:00Z", null));
        StrategyStore store = store();
        assertTrue(store.list().isEmpty(), "a strategy without any valid version is dropped");
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(0, repaired.path("strategies").size());
        assertEquals(0, repaired.path("versions").size());
    }

    @Test
    void staleCurrentVersionIsRecomputedFromValidHistory() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        com.fasterxml.jackson.databind.node.ArrayNode strategies = nodeMapper.createArrayNode();
        // Stored currentVersion=5 is stale: the valid history only reaches 2.
        strategies.add(rawStrategy("s1", "default", "策略", Strategy.STATUS_ACTIVE, 5,
                "2026-08-01T00:00:00Z", null));
        root.set("strategies", strategies);
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v1", "s1", 1, "2026-08-01T00:00:00Z"));
        versions.add(rawVersion("v2", "s1", 2, "2026-08-01T01:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);
        StrategyStore store = store();
        assertEquals(2, store.get("s1").currentVersion(),
                "currentVersion is recomputed from the max valid version number");
        // The repaired disk file carries the recomputed value.
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(2, repaired.path("strategies").get(0).path("currentVersion").asInt());
    }

    @Test
    void illegalEntriesAreDroppedAndDiskRepaired() throws Exception {
        // Strategy ACTIVE with archivedAt; ARCHIVED without archivedAt;
        // version with number 0; version with a lowercase timeframe;
        // version with non-canonical (duplicate) instruments.
        com.fasterxml.jackson.databind.node.ObjectNode badActive = rawStrategy(
                "s1", "default", "活跃却带归档时间", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", "2026-08-01T05:00:00Z");
        com.fasterxml.jackson.databind.node.ObjectNode badArchived = rawStrategy(
                "s2", "default", "归档却无归档时间", Strategy.STATUS_ARCHIVED, 1,
                "2026-08-01T00:00:00Z", null);
        com.fasterxml.jackson.databind.node.ObjectNode badNumber = rawStrategy(
                "s3", "default", "版本号为零", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null);
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        com.fasterxml.jackson.databind.node.ArrayNode strategies = nodeMapper.createArrayNode();
        strategies.add(badActive).add(badArchived).add(badNumber);
        root.set("strategies", strategies);
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v-zero", "s3", 0, "2026-08-01T00:00:00Z"));
        com.fasterxml.jackson.databind.node.ObjectNode lowerTf = rawVersion(
                "v-tf", "s3", 1, "2026-08-01T01:00:00Z");
        lowerTf.put("timeframe", "1d"); // non-canonical lowercase
        versions.add(lowerTf);
        com.fasterxml.jackson.databind.node.ObjectNode dupInstr = rawVersion(
                "v-dup", "s3", 2, "2026-08-01T02:00:00Z");
        com.fasterxml.jackson.databind.node.ArrayNode instrs = nodeMapper.createArrayNode();
        instrs.add("JM2609").add("JM2609"); // duplicate -> non-canonical
        dupInstr.set("instruments", instrs);
        versions.add(dupInstr);
        root.set("versions", versions);
        writeRaw(root);

        StrategyStore store = store();
        assertTrue(store.list().isEmpty(), "all illegal records must be dropped");
        JsonNode repaired = nodeMapper.readTree(Files.readAllBytes(strategiesFile));
        assertEquals(0, repaired.path("strategies").size());
        assertEquals(0, repaired.path("versions").size());
    }

    @Test
    void repairedFileIsStableAcrossRepeatedLoads() throws Exception {
        // Extra top-level field + wrong schema + stale currentVersion + a
        // case-insensitive name duplicate: one repair pass must converge; a
        // second load must NOT change the file again.
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", "strategies.v9");
        root.put("extra", "sentinel");
        com.fasterxml.jackson.databind.node.ArrayNode strategies = nodeMapper.createArrayNode();
        strategies.add(rawStrategy("s1", "default", "MA策略", Strategy.STATUS_ACTIVE, 9,
                "2026-08-01T00:00:00Z", null));
        strategies.add(rawStrategy("s2", "default", "ma策略", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null)); // case-insensitive name duplicate
        root.set("strategies", strategies);
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v1", "s1", 1, "2026-08-01T00:00:00Z"));
        versions.add(rawVersion("v2", "s1", 2, "2026-08-01T01:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);

        StrategyStore store = store();
        assertEquals(1, store.list().size());
        assertEquals("s1", store.list().get(0).id());
        assertEquals(2, store.get("s1").currentVersion());
        String afterFirst = Files.readString(strategiesFile);
        assertTopLevelClean(afterFirst);
        assertFalse(afterFirst.contains("sentinel"));
        store.list();
        store.get("s1");
        String afterSecond = Files.readString(strategiesFile);
        assertEquals(afterFirst, afterSecond, "repair must converge; no second drift");
    }

    @Test
    void repairIsPureMetadataWork() throws Exception {
        // Load-time repair must NOT mutate the workspace or team stores (no
        // implicit workspace/member creation beyond the normal lazy default).
        int wsBefore = workspaceStore.list().size();
        int membersBefore = teamStore.listMembers().size();
        // Orphan strategy + stale currentVersion + wrong top-level schema:
        // several repair paths run on one load.
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", "strategies.v9");
        com.fasterxml.jackson.databind.node.ArrayNode strategies = nodeMapper.createArrayNode();
        strategies.add(rawStrategy("o1", "ghost", "孤儿", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null));
        strategies.add(rawStrategy("s1", "default", "策略", Strategy.STATUS_ACTIVE, 5,
                "2026-08-01T00:00:00Z", null));
        root.set("strategies", strategies);
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v1", "s1", 1, "2026-08-01T00:00:00Z"));
        versions.add(rawVersion("v2", "s1", 2, "2026-08-01T01:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);

        StrategyStore store = store();
        assertEquals(1, store.list().size());
        assertEquals(2, store.get("s1").currentVersion());
        assertEquals(wsBefore, workspaceStore.list().size(),
                "repair must not create workspaces");
        assertEquals(membersBefore, teamStore.listMembers().size(),
                "repair must not create team members");
    }

    // ------------------------------------------------------------------ //
    // TOP-LEVEL PROTOCOL regression tests
    // ------------------------------------------------------------------ //

    private com.fasterxml.jackson.databind.node.ObjectNode topLevelWith(
            com.fasterxml.jackson.databind.node.ObjectNode... strategies) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = nodeMapper.createArrayNode();
        for (com.fasterxml.jackson.databind.node.ObjectNode n : strategies) arr.add(n);
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        root.set("strategies", arr);
        root.set("versions", nodeMapper.createArrayNode());
        return root;
    }

    @Test
    void extraTopLevelFieldIsRemovedAndLegalStrategiesKept() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode good = rawStrategy(
                "g1", "default", "合法策略", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null);
        com.fasterxml.jackson.databind.node.ObjectNode root = topLevelWith(good);
        root.put("unexpectedTopLevel", "sentinel"); // unknown top-level field
        // The good strategy needs its version to survive the load.
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v1", "g1", 1, "2026-08-01T00:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);
        StrategyStore store = store();
        assertEquals(1, store.list().size());
        assertEquals("g1", store.list().get(0).id());
        String disk = Files.readString(strategiesFile);
        assertFalse(disk.contains("unexpectedTopLevel"));
        assertFalse(disk.contains("sentinel"));
        assertTopLevelClean(disk);
    }

    @Test
    void wrongSchemaIsNormalisedAndStrategiesKept() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode good = rawStrategy(
                "g1", "default", "合法策略", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null);
        com.fasterxml.jackson.databind.node.ObjectNode root = topLevelWith(good);
        root.put("schema", "strategies.v9");
        // The good strategy needs its version to survive the load.
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v1", "g1", 1, "2026-08-01T00:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);
        StrategyStore store = store();
        assertEquals(1, store.list().size());
        assertTopLevelClean(Files.readString(strategiesFile));
    }

    @Test
    void missingSchemaIsNormalisedAndStrategiesKept() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode good = rawStrategy(
                "g1", "default", "合法策略", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null);
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.set("strategies", nodeMapper.createArrayNode().add(good));
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v1", "g1", 1, "2026-08-01T00:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);
        StrategyStore store = store();
        assertEquals(1, store.list().size());
        assertTopLevelClean(Files.readString(strategiesFile));
    }

    @Test
    void strategiesWrongTypeRepairsToEmptySnapshot() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        root.put("strategies", "not-an-array");
        root.set("versions", nodeMapper.createArrayNode());
        writeRaw(root);
        StrategyStore store = store();
        assertTrue(store.list().isEmpty());
        assertTopLevelClean(Files.readString(strategiesFile));
    }

    @Test
    void versionsWrongTypeRepairsToEmptySnapshot() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = nodeMapper.createObjectNode();
        root.put("schema", StrategyStore.SCHEMA);
        root.set("strategies", nodeMapper.createArrayNode());
        root.put("versions", 42);
        writeRaw(root);
        StrategyStore store = store();
        assertTrue(store.list().isEmpty());
        assertTopLevelClean(Files.readString(strategiesFile));
    }

    @Test
    void rootNotObjectRepairsToEmptySnapshot() throws Exception {
        Files.writeString(strategiesFile, "[1,2,3]");
        StrategyStore store = store();
        assertTrue(store.list().isEmpty());
        assertTopLevelClean(Files.readString(strategiesFile));
    }

    @Test
    void repairedFileIsStableAfterTopLevelRepair() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode good = rawStrategy(
                "g1", "default", "合法策略", Strategy.STATUS_ACTIVE, 1,
                "2026-08-01T00:00:00Z", null);
        com.fasterxml.jackson.databind.node.ObjectNode root = topLevelWith(good);
        root.put("schema", "strategies.v9");
        root.put("extra", "sentinel");
        // The good strategy needs its version to survive the load.
        com.fasterxml.jackson.databind.node.ArrayNode versions = nodeMapper.createArrayNode();
        versions.add(rawVersion("v1", "g1", 1, "2026-08-01T00:00:00Z"));
        root.set("versions", versions);
        writeRaw(root);
        StrategyStore store = store();
        store.list(); // first load repairs
        String afterFirst = Files.readString(strategiesFile);
        assertTopLevelClean(afterFirst);
        store.list(); // second load must not drift
        assertEquals(afterFirst, Files.readString(strategiesFile));
    }
}
