package com.supertrader.demo.strategy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supertrader.demo.team.TeamMember;
import com.supertrader.demo.team.TeamStore;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local, disk-persisted store of strategy RESEARCH metadata + the immutable
 * version history (Module 8).
 *
 * <p>Contract:
 * <ul>
 *   <li>Persists ONLY the whitelisted metadata fields of {@link Strategy} and
 *       {@link StrategyVersion} — never credentials, tokens, private keys,
 *       accounts, order/cancel fields or any trading-capable field.</li>
 *   <li>Persists to a single JSON file whose default location is
 *       {@code ../.run/strategies.json} relative to the backend working dir
 *       (backend/), i.e. {@code rebuild/.run/strategies.json} — the ONLY
 *       allowed location, Git-ignored. The environment variable
 *       {@code SIMNOW_STRATEGIES_FILE} overrides it (integration tests MUST
 *       point it at a validated mkdtemp directory).</li>
 *   <li>This store ONLY manages research definitions. It NEVER runs, backtests
 *       or signals from a strategy, NEVER binds a SimNow account, NEVER calls
 *       the diagnosis/probe services and NEVER creates AgentScope tasks.
 *       Loading / repairing / starting / page opening never trigger any
 *       external call.</li>
 *   <li>SAFE RECOVERY: a missing / empty / corrupt file, or a file holding
 *       illegal entries, never fails startup or any request — the store
 *       repairs it deterministically and writes the clean snapshot back
 *       atomically (see {@link #loadAndRepair()}).</li>
 *   <li>Strict per-workspace isolation: every read/write is scoped to the
 *       CURRENT workspace (from {@link WorkspaceStore}); a strategy of another
 *       workspace is deliberately "not found" (404), so cross-workspace reads
 *       or mutations are impossible. Requests NEVER carry a workspaceId,
 *       strategy id, status, currentVersion, actor or timestamp — those are
 *       always derived from server state.</li>
 *   <li>Versions are APPEND-ONLY: they can never be updated, overwritten,
 *       deleted, rolled back or renumbered; versionNumber starts at 1 and is
 *       strictly increasing. currentVersion is ALWAYS recomputed from the
 *       valid version records, never taken from a client.</li>
 *   <li>Within a workspace a strategy name is unique case-insensitively on
 *       the trimmed name; an ARCHIVED strategy keeps its name reserved.</li>
 *   <li>ALL permission decisions are enforced HERE in the server (RBAC
 *       re-checked on every write). The frontend may hide buttons, but that is
 *       NOT access control.</li>
 *   <li>There is NO deletion of strategies by design (archive replaces it);
 *       the API exposes NO delete / run / execute / backtest / signal /
 *       publish / approve / bind / order / cancel / recover / auto-trade
 *       routes.</li>
 * </ul>
 *
 * <p>Concurrency: all mutating/reading entry points are {@code synchronized};
 * the file is read on every operation and written atomically (tmp + move).
 */
@Component
public class StrategyStore {

    private static final Logger log = LoggerFactory.getLogger(StrategyStore.class);

    static final String SCHEMA = "strategies.v1";
    static final int MAX_NAME = 64;
    static final int MAX_SUMMARY = 500;
    static final int MAX_RULE_LENGTH = 4000;
    static final int MAX_INSTRUMENTS = 20;
    static final int MAX_INSTRUMENT_LENGTH = 16;
    /** Server-side whitelist of timeframes (canonical, uppercased). */
    static final Set<String> TIMEFRAMES = Set.of(
            "TICK", "1M", "5M", "15M", "30M", "1H", "1D");

    private final ObjectMapper mapper = new ObjectMapper()
            // A record carrying an unknown (e.g. credential-shaped) field is
            // deliberately rejected so it can be dropped and scrubbed from disk.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private final Path file;
    private final WorkspaceStore workspaceStore;
    private final TeamStore teamStore;

    public StrategyStore(@Value("${app.strategies.file}") String strategiesFile,
                         WorkspaceStore workspaceStore,
                         TeamStore teamStore) {
        this.file = Path.of(strategiesFile);
        this.workspaceStore = workspaceStore;
        this.teamStore = teamStore;
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

    /** Strategies of the current workspace only (active + archived), oldest
     *  first. May be empty. */
    public synchronized List<Strategy> list() {
        String wsId = currentWorkspaceId();
        return loadAndRepair().strategies().stream()
                .filter(s -> s.workspaceId().equals(wsId))
                .sorted(Comparator.comparing(Strategy::createdAt))
                .toList();
    }

    /** The strategy of the current workspace with the given id, or a 404. A
     *  strategy of ANOTHER workspace is deliberately "not found" (isolation,
     *  no existence leak). */
    public synchronized Strategy get(String id) {
        List<Strategy> snap = loadAndRepair().strategies();
        return byIdOrThrow(snap, currentWorkspaceId(), id);
    }

    /** The immutable version history of the strategy, ascending version
     *  number. The strategy must belong to the CURRENT workspace (404
     *  otherwise). */
    public synchronized List<StrategyVersion> versions(String id) {
        Strategy target = get(id); // 404 + isolation boundary
        return loadAndRepair().versions().stream()
                .filter(v -> v.strategyId().equals(target.id()))
                .sorted(Comparator.comparingInt(StrategyVersion::versionNumber))
                .toList();
    }

    /** Whether a version with the given id exists anywhere (regardless of
     *  workspace). Used ONLY by the Module 9 freeze-transaction recovery to
     *  decide whether a crashed freeze already appended its version. Pure
     *  metadata read; never triggers any external call. */
    public synchronized boolean hasVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) return false;
        Snapshot snap = loadAndRepair();
        return snap.versions().stream().anyMatch(v -> v.id().equals(versionId));
    }

    /** The version with the given id, or null. Used ONLY by the Module 9
     *  freeze-transaction recovery to learn the version number that a crashed
     *  freeze already appended (ids are pre-generated, the number is
     *  server-computed). Pure metadata read. */
    public synchronized StrategyVersion versionById(String versionId) {
        if (versionId == null || versionId.isBlank()) return null;
        Snapshot snap = loadAndRepair();
        return snap.versions().stream()
                .filter(v -> v.id().equals(versionId))
                .findFirst().orElse(null);
    }

    /** The workspace of the strategy with the given id, or {@code null} when
     *  the strategy does not exist ANYWHERE (regardless of workspace). Used
     *  ONLY by the Module 10 risk-control store repair to detect orphan /
     *  cross-workspace strategy references. Pure metadata read; never triggers
     *  any external call. */
    public synchronized String strategyWorkspaceAny(String strategyId) {
        if (strategyId == null || strategyId.isBlank()) return null;
        Snapshot snap = loadAndRepair();
        return snap.strategies().stream()
                .filter(s -> s.id().equals(strategyId))
                .map(com.supertrader.demo.strategy.Strategy::workspaceId)
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ //
    // Write API
    //
    // Every write derives the workspace + the current actor from SERVER state
    // and re-checks the RBAC matrix. A request body can never carry a
    // workspaceId, strategy id, status, currentVersion, actor or timestamp.
    // ------------------------------------------------------------------ //

    /**
     * Create a strategy AND its version 1 in ONE atomic write. The name is
     * validated server-side (case-insensitively unique per workspace); the
     * version definition is validated and normalized into its canonical form;
     * the id, workspaceId, status, currentVersion, createdByMemberId and all
     * timestamps are server-derived — client values are never adopted.
     */
    public synchronized StrategyDtos.StrategyResponse create(StrategyDtos.CreateRequest body) {
        ensureCanCreate();                               // 403 FORBIDDEN
        if (body == null) {
            throw StrategyApiException.invalidBody("请求体必须是合法的 JSON 对象");
        }
        String name = validateName(body.name());        // 400 INVALID_STRATEGY_NAME
        StrategyVersion v1 = buildVersion(null, 1, body.summary(), body.instruments(),
                body.timeframe(), body.entryRules(), body.exitRules(), body.riskNotes(),
                currentMemberId(), true);               // 400 INVALID_STRATEGY_VERSION/INSTRUMENT/TIMEFRAME

        Snapshot snap = loadAndRepair();
        String wsId = currentWorkspaceId();
        if (hasName(snap.strategies(), wsId, name, null)) {
            throw StrategyApiException.duplicateStrategy(
                    "当前工作空间已存在同名策略（名称不区分大小写，归档策略名称保留）");
        }
        String now = Instant.now().toString();
        Strategy created = new Strategy(
                UUID.randomUUID().toString(), wsId, name,
                Strategy.STATUS_ACTIVE, 1, v1.createdByMemberId(), now, now, null);
        StrategyVersion version = withStrategyId(v1, created.id(), now);
        persist(snap.withAdded(created, version));
        return new StrategyDtos.StrategyResponse("strategies.strategy.v1", created);
    }

    /** Rename a strategy (name only; server-side name rules + uniqueness). */
    public synchronized StrategyDtos.StrategyResponse rename(String id, String rawName) {
        ensureCanManage();                               // 403 FORBIDDEN
        String name = validateName(rawName);             // 400 INVALID_STRATEGY_NAME
        Snapshot snap = loadAndRepair();
        Strategy target = byIdOrThrow(snap.strategies(), currentWorkspaceId(), id); // 404
        if (Strategy.STATUS_ARCHIVED.equals(target.status())) {
            throw StrategyApiException.strategyArchived("已归档策略不可重命名");
        }
        if (hasName(snap.strategies(), target.workspaceId(), name, target.id())) {
            throw StrategyApiException.duplicateStrategy(
                    "当前工作空间已存在同名策略（名称不区分大小写，归档策略名称保留）");
        }
        String now = Instant.now().toString();
        Strategy renamed = new Strategy(
                target.id(), target.workspaceId(), name, target.status(),
                target.currentVersion(), target.createdByMemberId(),
                target.createdAt(), now, target.archivedAt());
        // Locate by id AND workspaceId: a corrupt file with a duplicate id must
        // never let a mutation rewrite another workspace's record.
        List<Strategy> next = new ArrayList<>(snap.strategies());
        next.replaceAll(s -> s.id().equals(id) && s.workspaceId().equals(target.workspaceId())
                ? renamed : s);
        persist(new Snapshot(next, snap.versions()));
        return new StrategyDtos.StrategyResponse("strategies.strategy.v1", renamed);
    }

    /**
     * Append an IMMUTABLE version. The version number is server-computed
     * (current max + 1) and NEVER adopted from the client. The strategy must
     * be ACTIVE (an archived strategy cannot be versioned).
     */
    public synchronized StrategyDtos.VersionResponse appendVersion(
            String id, StrategyDtos.AppendVersionRequest body) {
        ensureCanCreate();                               // 403 FORBIDDEN
        if (body == null) {
            throw StrategyApiException.invalidBody("请求体必须是合法的 JSON 对象");
        }
        Snapshot snap = loadAndRepair();
        Strategy target = byIdOrThrow(snap.strategies(), currentWorkspaceId(), id); // 404
        if (Strategy.STATUS_ARCHIVED.equals(target.status())) {
            throw StrategyApiException.strategyArchived("已归档策略不可追加版本");
        }
        int nextNumber = snap.versions().stream()
                .filter(v -> v.strategyId().equals(target.id()))
                .mapToInt(StrategyVersion::versionNumber)
                .max().orElse(0) + 1;
        StrategyVersion version = buildVersion(target.id(), nextNumber,
                body.summary(), body.instruments(), body.timeframe(),
                body.entryRules(), body.exitRules(), body.riskNotes(),
                currentMemberId(), true);
        Strategy updated = new Strategy(
                target.id(), target.workspaceId(), target.name(), target.status(),
                nextNumber, target.createdByMemberId(), target.createdAt(),
                Instant.now().toString(), target.archivedAt());
        List<Strategy> nextStrategies = new ArrayList<>(snap.strategies());
        nextStrategies.replaceAll(s -> s.id().equals(id)
                && s.workspaceId().equals(target.workspaceId()) ? updated : s);
        List<StrategyVersion> nextVersions = new ArrayList<>(snap.versions());
        nextVersions.add(version);
        persist(new Snapshot(nextStrategies, nextVersions));
        return new StrategyDtos.VersionResponse("strategies.version.v1", version);
    }

    /** Archive a strategy (metadata-only; it stays readable with its full
     *  version history; its name stays reserved). */
    public synchronized StrategyDtos.StrategyResponse archive(String id) {
        ensureCanManage();                               // 403 FORBIDDEN
        Snapshot snap = loadAndRepair();
        Strategy target = byIdOrThrow(snap.strategies(), currentWorkspaceId(), id); // 404
        if (Strategy.STATUS_ARCHIVED.equals(target.status())) {
            throw StrategyApiException.strategyArchived("策略已归档，无需重复归档");
        }
        String now = Instant.now().toString();
        Strategy archived = new Strategy(
                target.id(), target.workspaceId(), target.name(),
                Strategy.STATUS_ARCHIVED, target.currentVersion(),
                target.createdByMemberId(), target.createdAt(), now, now);
        List<Strategy> next = new ArrayList<>(snap.strategies());
        next.replaceAll(s -> s.id().equals(id) && s.workspaceId().equals(target.workspaceId())
                ? archived : s);
        persist(new Snapshot(next, snap.versions()));
        return new StrategyDtos.StrategyResponse("strategies.strategy.v1", archived);
    }

    // ------------------------------------------------------------------ //
    // Module 9 freeze-transaction recovery support (INTERNAL ONLY)
    //
    // The Module 9 approve-and-freeze transaction spans TWO stores
    // (strategies.json + task-center.json) which cannot be replaced in one
    // atomic move. To guarantee no half-frozen state it uses a recoverable
    // transaction marker and deterministically replays the interrupted write
    // on the next load. The three methods below are the replay primitives:
    // they use PRE-GENERATED ids so a replay is idempotent (checking
    // {@link #hasVersion} first). They are NEVER called by any client route —
    // they bypass RBAC on purpose and are documented as internal recovery
    // only. Content is still validated exactly like a normal write.
    // ------------------------------------------------------------------ //

    /**
     * Recovery-only: create a strategy AND its version with server-fixed ids.
     * Used to replay a crashed Module 9 freeze that was creating a NEW
     * strategy. All values are re-validated; the name-uniqueness check runs so
     * a replay can never create a duplicate name (a conflicting name fails the
     * recovery loudly instead of silently corrupting the library).
     */
    public synchronized StrategyDtos.StrategyResponse createRecoveredStrategy(
            String strategyId, String versionId, String wsId, String name,
            String summary, List<String> instruments, String timeframe,
            String entryRules, String exitRules, String riskNotes,
            String memberId, String createdAt, String versionCreatedAt) {
        StrategyVersion v1 = buildVersion(null, 1, summary, instruments, timeframe,
                entryRules, exitRules, riskNotes, memberId, true);
        v1 = new StrategyVersion(versionId, v1.strategyId(), 1, v1.summary(),
                v1.instruments(), v1.timeframe(), v1.entryRules(), v1.exitRules(),
                v1.riskNotes(), v1.createdByMemberId(), versionCreatedAt);
        Snapshot snap = loadAndRepair();
        if (hasName(snap.strategies(), wsId, name, null)) {
            throw StrategyApiException.duplicateStrategy(
                    "冻结恢复失败：当前工作空间已存在同名策略（名称不区分大小写）");
        }
        Strategy created = new Strategy(
                strategyId, wsId, name, Strategy.STATUS_ACTIVE, 1, memberId,
                createdAt, createdAt, null);
        v1 = withStrategyId(v1, strategyId, versionCreatedAt);
        persist(snap.withAdded(created, v1));
        return new StrategyDtos.StrategyResponse("strategies.strategy.v1", created);
    }

    /**
     * Recovery-only: append an immutable version with a server-fixed version
     * id. The version NUMBER is still computed by the server (max+1) — only
     * the id is pre-generated so a replay is idempotent. Used to replay a
     * crashed Module 9 freeze on an EXISTING strategy. RBAC is bypassed on
     * purpose (internal recovery only); content is fully validated.
     */
    public synchronized StrategyDtos.VersionResponse appendVersionWithId(
            String id, String versionId, StrategyDtos.AppendVersionRequest body,
            String memberId) {
        if (body == null) {
            throw StrategyApiException.invalidBody("请求体必须是合法的 JSON 对象");
        }
        Snapshot snap = loadAndRepair();
        Strategy target = byIdOrThrow(snap.strategies(), currentWorkspaceId(), id); // 404
        if (Strategy.STATUS_ARCHIVED.equals(target.status())) {
            throw StrategyApiException.strategyArchived("已归档策略不可追加版本");
        }
        int nextNumber = snap.versions().stream()
                .filter(v -> v.strategyId().equals(target.id()))
                .mapToInt(StrategyVersion::versionNumber)
                .max().orElse(0) + 1;
        StrategyVersion version = buildVersion(target.id(), nextNumber,
                body.summary(), body.instruments(), body.timeframe(),
                body.entryRules(), body.exitRules(), body.riskNotes(),
                memberId, true);
        version = new StrategyVersion(versionId, version.strategyId(),
                version.versionNumber(), version.summary(), version.instruments(),
                version.timeframe(), version.entryRules(), version.exitRules(),
                version.riskNotes(), version.createdByMemberId(), version.createdAt());
        Strategy updated = new Strategy(
                target.id(), target.workspaceId(), target.name(), target.status(),
                nextNumber, target.createdByMemberId(), target.createdAt(),
                Instant.now().toString(), target.archivedAt());
        List<Strategy> nextStrategies = new ArrayList<>(snap.strategies());
        nextStrategies.replaceAll(s -> s.id().equals(id)
                && s.workspaceId().equals(target.workspaceId()) ? updated : s);
        List<StrategyVersion> nextVersions = new ArrayList<>(snap.versions());
        nextVersions.add(version);
        persist(new Snapshot(nextStrategies, nextVersions));
        return new StrategyDtos.VersionResponse("strategies.version.v1", version);
    }

    // ------------------------------------------------------------------ //
    // Server-side RBAC + validation
    // ------------------------------------------------------------------ //

    /** OWNER / ADMIN / TRADER may create strategies and append versions;
     *  VIEWER is read-only. */
    private void ensureCanCreate() {
        String role = currentRole();
        if (TeamStore.ROLE_VIEWER.equals(role)) {
            throw StrategyApiException.forbidden(
                    "当前角色（VIEWER）不可新建策略或追加版本（仅 OWNER / ADMIN / TRADER）");
        }
    }

    /** Only OWNER / ADMIN may rename or archive. */
    private void ensureCanManage() {
        String role = currentRole();
        if (!TeamStore.ROLE_OWNER.equals(role) && !TeamStore.ROLE_ADMIN.equals(role)) {
            throw StrategyApiException.forbidden(
                    "当前角色（" + role + "）无重命名/归档策略权限（仅 OWNER / ADMIN）");
        }
    }

    private String currentRole() {
        TeamMember actor = teamStore.currentActor();
        return actor == null ? TeamStore.ROLE_VIEWER : actor.role();
    }

    private String currentMemberId() {
        TeamMember actor = teamStore.currentActor();
        return actor == null ? null : actor.id();
    }

    /**
     * Validate a strategy name: required, trimmed, 1..{@value #MAX_NAME}
     * chars, no control characters. Returns the trimmed name or throws
     * {@link StrategyApiException#invalidStrategyName}.
     */
    static String validateName(String rawName) {
        if (rawName == null) {
            throw StrategyApiException.invalidStrategyName("策略名称不能为空");
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            throw StrategyApiException.invalidStrategyName("策略名称不能为空");
        }
        if (name.length() > MAX_NAME) {
            throw StrategyApiException.invalidStrategyName(
                    "策略名称过长（最多 " + MAX_NAME + " 个字符）");
        }
        rejectControlChars(name, StrategyApiException::invalidStrategyName,
                "策略名称包含不允许的控制字符");
        return name;
    }

    /** Validate a summary: trimmed, 1..{@value #MAX_SUMMARY} chars, no control
     *  characters. */
    static String validateSummary(String raw) {
        if (raw == null) {
            throw StrategyApiException.invalidStrategyVersion("版本摘要不能为空");
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            throw StrategyApiException.invalidStrategyVersion("版本摘要不能为空");
        }
        if (s.length() > MAX_SUMMARY) {
            throw StrategyApiException.invalidStrategyVersion(
                    "版本摘要过长（最多 " + MAX_SUMMARY + " 个字符）");
        }
        rejectControlChars(s, StrategyApiException::invalidStrategyVersion,
                "版本摘要包含不允许的控制字符");
        return s;
    }

    /** Validate a free-text rule (entry/exit/risk): trimmed, at most
     *  {@value #MAX_RULE_LENGTH} chars, no control characters. A missing rule
     *  is treated as an empty string (rules are optional content). */
    static String validateRule(String raw, String label) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() > MAX_RULE_LENGTH) {
            throw StrategyApiException.invalidStrategyVersion(
                    label + "过长（最多 " + MAX_RULE_LENGTH + " 个字符）");
        }
        rejectControlChars(s, StrategyApiException::invalidStrategyVersion,
                label + "包含不允许的控制字符");
        return s;
    }

    /**
     * Validate + canonicalize the instrument list: 1..{@value #MAX_INSTRUMENTS}
     * DISTINCT items after de-duplication, each 1..{@value #MAX_INSTRUMENT_LENGTH}
     * letters/digits. The canonical form is deterministic: every id is
     * uppercased and duplicates are removed keeping the first-seen order.
     */
    static List<String> validateInstruments(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw StrategyApiException.invalidInstrument(
                    "合约列表不能为空（至少 1 个公开合约代码，如 JM2609）");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                throw StrategyApiException.invalidInstrument("合约列表包含空项");
            }
            String s = item.trim();
            if (s.isEmpty() || s.length() > MAX_INSTRUMENT_LENGTH) {
                throw StrategyApiException.invalidInstrument(
                        "合约代码长度需为 1–" + MAX_INSTRUMENT_LENGTH + " 个字符");
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9'))) {
                    throw StrategyApiException.invalidInstrument(
                            "合约代码只能包含字母与数字（公开合约代码，如 JM2609）");
                }
            }
            out.add(s.toUpperCase(Locale.ROOT));
        }
        // The 1..20 limit applies AFTER de-duplication (deterministic).
        if (out.isEmpty() || out.size() > MAX_INSTRUMENTS) {
            throw StrategyApiException.invalidInstrument(
                    "去重后合约数量需为 1–" + MAX_INSTRUMENTS + " 个");
        }
        return List.copyOf(out);
    }

    /** Validate the timeframe against the server-side whitelist; returns the
     *  canonical uppercased value. */
    static String validateTimeframe(String raw) {
        if (raw == null || raw.isBlank()) {
            throw StrategyApiException.invalidTimeframe(
                    "周期不能为空（可选：TICK、1M、5M、15M、30M、1H、1D）");
        }
        String tf = raw.trim().toUpperCase(Locale.ROOT);
        if (!TIMEFRAMES.contains(tf)) {
            throw StrategyApiException.invalidTimeframe(
                    "不支持的周期（可选：TICK、1M、5M、15M、30M、1H、1D）");
        }
        return tf;
    }

    private interface NameThrower {
        StrategyApiException throwIt(String message);
    }

    private static void rejectControlChars(String s, NameThrower thrower, String message) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw thrower.throwIt(message);
            }
        }
    }

    /** Build a version record from validated canonical parts. When
     *  {@code withStrategy} is false the strategyId is filled later (the id is
     *  generated inside create, atomically with the version). */
    private static StrategyVersion buildVersion(String strategyId, int number,
                                                String summary, List<String> instruments,
                                                String timeframe, String entryRules,
                                                String exitRules, String riskNotes,
                                                String memberId, boolean withStrategy) {
        return new StrategyVersion(
                UUID.randomUUID().toString(),
                withStrategy ? strategyId : "",
                number,
                validateSummary(summary),
                validateInstruments(instruments),
                validateTimeframe(timeframe),
                validateRule(entryRules, "入场规则"),
                validateRule(exitRules, "出场规则"),
                validateRule(riskNotes, "风控备注"),
                memberId == null ? "" : memberId,
                Instant.now().toString());
    }

    private static StrategyVersion withStrategyId(StrategyVersion v, String strategyId, String createdAt) {
        return new StrategyVersion(v.id(), strategyId, v.versionNumber(), v.summary(),
                v.instruments(), v.timeframe(), v.entryRules(), v.exitRules(),
                v.riskNotes(), v.createdByMemberId(), createdAt);
    }

    // ------------------------------------------------------------------ //
    // Snapshot load + deterministic repair
    // ------------------------------------------------------------------ //

    /** What {@code readFile()} found + anything that had to be skipped. */
    private record ReadResult(List<Strategy> strategies, List<StrategyVersion> versions,
                              boolean repaired) {}

    /** The full valid state of the store (strategies + versions). */
    private record Snapshot(List<Strategy> strategies, List<StrategyVersion> versions) {

        Snapshot withAdded(Strategy s, StrategyVersion v) {
            List<Strategy> ns = new ArrayList<>(strategies);
            ns.add(s);
            List<StrategyVersion> nv = new ArrayList<>(versions);
            nv.add(v);
            return new Snapshot(ns, nv);
        }
    }

    private Strategy byIdOrThrow(List<Strategy> snap, String wsId, String id) {
        for (Strategy s : snap) {
            if (s.id().equals(id) && s.workspaceId().equals(wsId)) return s;
        }
        // A strategy of ANOTHER workspace is deliberately "not found" here —
        // this is the server-side isolation boundary (no existence leak).
        throw StrategyApiException.strategyNotFound("当前工作空间不存在该策略：" + id);
    }

    private static boolean hasName(List<Strategy> snap, String wsId, String name,
                                   String excludeId) {
        for (Strategy s : snap) {
            if (!s.workspaceId().equals(wsId)) continue;
            if (excludeId != null && s.id().equals(excludeId)) continue;
            // Case-insensitive on the trimmed name; ARCHIVED names stay
            // reserved (they are still compared).
            if (s.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Load the full state and restore the GLOBAL invariants deterministically.
     * Every load distinguishes:
     *  - a fully valid file (nothing rewritten);
     *  - an unreadable record (unknown/extra fields, JSON that cannot map) —
     *    dropped;
     *  - a record with illegal fields / values — dropped;
     *  - an orphan strategy (workspace no longer exists) — dropped;
     *  - an orphan version (its strategy does not survive) — dropped;
     *  - a duplicate strategy id — only the deterministic first is kept
     *    (earliest createdAt, tie-break by id);
     *  - a duplicate case-insensitive strategy name in one workspace — only the
     *    deterministic first is kept;
     *  - a duplicate version id — only the deterministic first is kept;
     *  - a duplicate versionNumber for the same strategy — only the
     *    deterministic first is kept;
     *  - a strategy left WITHOUT any valid version — dropped (every strategy
     *    is created with version 1);
     *  - {@code currentVersion} — ALWAYS recomputed as the maximum valid
     *    version number of the repaired history (never renumbered, never taken
     *    from the file).
     *
     * <p>Whenever anything is dropped or normalized, the cleaned whitelisted
     * snapshot is written back ATOMICALLY so illegal fields can never linger
     * on disk; the repair converges — a second load never drifts. Logs only
     * carry the sanitised id + workspaceId + the repair type — never the
     * record content, credential-shaped fields or raw entries. This method is
     * pure metadata work: it NEVER calls the diagnosis service, NEVER starts
     * the probe and NEVER makes any external call.
     */
    private Snapshot loadAndRepair() {
        ReadResult read = readFile();
        boolean repaired = read.repaired();
        List<String> wsIds = workspaceStore.list().stream()
                .map(com.supertrader.demo.workspace.Workspace::id)
                .toList();

        // Pass 1: per-entry legality (orphan workspace / illegal fields).
        List<Strategy> keptStrategies = new ArrayList<>();
        for (Strategy s : read.strategies()) {
            if (!wsIds.contains(s.workspaceId())) {
                log.warn("Repair: dropped orphan strategy id={} workspace={}", s.id(), s.workspaceId());
                repaired = true;
                continue;
            }
            if (!isValidStrategy(s)) {
                log.warn("Repair: dropped illegal strategy entry id={} workspace={}",
                        s.id(), s.workspaceId());
                repaired = true;
                continue;
            }
            keptStrategies.add(s);
        }

        // Deterministic order: createdAt (ISO-8601, already validated), then id.
        List<Strategy> orderedStrategies = new ArrayList<>(keptStrategies);
        orderedStrategies.sort(Comparator
                .comparing((Strategy s) -> Instant.parse(s.createdAt()))
                .thenComparing(Strategy::id));

        // Pass 2: globally-unique strategy ids — keep the deterministic first.
        Set<String> seenIds = new HashSet<>();
        List<Strategy> uniqueStrategies = new ArrayList<>();
        for (Strategy s : orderedStrategies) {
            if (!seenIds.add(s.id())) {
                log.warn("Repair: dropped duplicate strategy id={} workspace={}",
                        s.id(), s.workspaceId());
                repaired = true;
                continue;
            }
            uniqueStrategies.add(s);
        }

        // Pass 3: case-insensitively unique strategy names per workspace —
        // keep the deterministic first (createdAt, then id). ARCHIVED names
        // stay reserved.
        Map<String, Set<String>> namesSeen = new HashMap<>();
        List<Strategy> uniqueNames = new ArrayList<>();
        for (Strategy s : uniqueStrategies) {
            Set<String> seen = namesSeen.computeIfAbsent(s.workspaceId(), k -> new HashSet<>());
            String key = s.name().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                log.warn("Repair: dropped duplicate strategy name id={} workspace={}",
                        s.id(), s.workspaceId());
                repaired = true;
                continue;
            }
            uniqueNames.add(s);
        }

        // Pass 4: versions — per-entry legality first.
        Set<String> keptStrategyIds = new HashSet<>();
        for (Strategy s : uniqueNames) keptStrategyIds.add(s.id());
        List<StrategyVersion> keptVersions = new ArrayList<>();
        for (StrategyVersion v : read.versions()) {
            if (!keptStrategyIds.contains(v.strategyId())) {
                log.warn("Repair: dropped orphan version id={} strategy={}",
                        v.id(), v.strategyId());
                repaired = true;
                continue;
            }
            if (!isValidVersion(v)) {
                log.warn("Repair: dropped illegal version entry id={} strategy={}",
                        v.id(), v.strategyId());
                repaired = true;
                continue;
            }
            keptVersions.add(v);
        }
        List<StrategyVersion> orderedVersions = new ArrayList<>(keptVersions);
        orderedVersions.sort(Comparator
                .comparing((StrategyVersion v) -> Instant.parse(v.createdAt()))
                .thenComparing(StrategyVersion::id));

        // Pass 5: globally-unique version ids.
        Set<String> seenVersionIds = new HashSet<>();
        List<StrategyVersion> uniqueVersions = new ArrayList<>();
        for (StrategyVersion v : orderedVersions) {
            if (!seenVersionIds.add(v.id())) {
                log.warn("Repair: dropped duplicate version id={} strategy={}",
                        v.id(), v.strategyId());
                repaired = true;
                continue;
            }
            uniqueVersions.add(v);
        }

        // Pass 6: one version per (strategyId, versionNumber) — keep the
        // deterministic first (createdAt, then id).
        Map<String, Set<Integer>> numbersSeen = new HashMap<>();
        List<StrategyVersion> uniqueNumbers = new ArrayList<>();
        for (StrategyVersion v : uniqueVersions) {
            Set<Integer> seen = numbersSeen.computeIfAbsent(v.strategyId(), k -> new HashSet<>());
            if (!seen.add(v.versionNumber())) {
                log.warn("Repair: dropped duplicate versionNumber id={} strategy={} number={}",
                        v.id(), v.strategyId(), v.versionNumber());
                repaired = true;
                continue;
            }
            uniqueNumbers.add(v);
        }

        // Pass 7: recompute currentVersion per strategy (max valid version
        // number; NEVER renumbered) and drop strategies left without ANY
        // version (every strategy is created with version 1).
        Map<String, Integer> maxNumber = new HashMap<>();
        for (StrategyVersion v : uniqueNumbers) {
            maxNumber.merge(v.strategyId(), v.versionNumber(), Math::max);
        }
        List<Strategy> normalized = new ArrayList<>();
        for (Strategy s : uniqueNames) {
            Integer max = maxNumber.get(s.id());
            if (max == null) {
                log.warn("Repair: dropped strategy without any version id={} workspace={}",
                        s.id(), s.workspaceId());
                repaired = true;
                continue;
            }
            if (s.currentVersion() != max) {
                log.warn("Repair: recomputed currentVersion id={} workspace={} {}->{}",
                        s.id(), s.workspaceId(), s.currentVersion(), max);
                repaired = true;
            }
            normalized.add(new Strategy(s.id(), s.workspaceId(), s.name(), s.status(),
                    max, s.createdByMemberId(), s.createdAt(), s.updatedAt(), s.archivedAt()));
        }

        Snapshot result = new Snapshot(normalized, uniqueNumbers);
        // Any repair (including records skipped while reading) is persisted
        // atomically: the disk must never keep illegal fields, duplicate ids,
        // duplicate names/numbers or a stale currentVersion.
        if (repaired) persist(result);
        return result;
    }

    /**
     * Read the file and validate the TOP-LEVEL protocol strictly:
     * <ul>
     *   <li>the root node MUST be a JSON object;</li>
     *   <li>the ONLY allowed top-level fields are {@code schema},
     *       {@code strategies} and {@code versions};</li>
     *   <li>{@code schema} MUST exist, be a string, and equal
     *       {@value #SCHEMA} exactly;</li>
     *   <li>{@code strategies} and {@code versions} MUST exist and be arrays.</li>
     * </ul>
     * Any violation enters the deterministic repair flow: a still-readable
     * array is kept (with the readable records), unknown top-level fields are
     * dropped, the schema is normalised, and {@code repaired=true} lets
     * {@link #loadAndRepair()} continue the global repair and write the clean
     * snapshot back atomically; an array that is missing / not an array (or a
     * broken file) safely degrades to an empty list.
     *
     * <p>No record content, unknown-field name or exception payload is ever
     * logged — only the repair type.
     */
    private ReadResult readFile() {
        if (!Files.exists(file)) return new ReadResult(new ArrayList<>(), new ArrayList<>(), false);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return new ReadResult(new ArrayList<>(), new ArrayList<>(), false);
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                log.warn("Repair: strategy file root is not an object, will repair to empty");
                return new ReadResult(new ArrayList<>(), new ArrayList<>(), true);
            }
            boolean topLevelRepair = false;

            // 1. Top-level field whitelist: ONLY schema + strategies + versions.
            java.util.Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                if (!"schema".equals(name) && !"strategies".equals(name) && !"versions".equals(name)) {
                    // Deliberately NOT logging the field name: it may be a
                    // credential-shaped key.
                    log.warn("Repair: dropped an unknown top-level field in strategy file");
                    topLevelRepair = true;
                }
            }

            // 2. schema must exist, be a string, and equal SCHEMA exactly.
            JsonNode schemaNode = root.path("schema");
            if (!schemaNode.isTextual() || !SCHEMA.equals(schemaNode.asText())) {
                log.warn("Repair: strategy file schema missing/wrong, will normalise");
                topLevelRepair = true;
            }

            // 3. strategies must exist and be an array.
            JsonNode strategiesNode = root.path("strategies");
            List<Strategy> strategies = new ArrayList<>();
            boolean strategiesBroken = false;
            if (strategiesNode.isArray()) {
                boolean skipped = false;
                for (JsonNode n : strategiesNode) {
                    try {
                        // FAIL_ON_UNKNOWN_PROPERTIES=true means a record carrying
                        // an unknown (e.g. credential-shaped) field is rejected.
                        strategies.add(mapper.treeToValue(n, Strategy.class));
                    } catch (Exception skip) {
                        log.warn("Repair: dropped an unreadable strategy entry (id=?)");
                        skipped = true;
                    }
                }
                if (skipped) topLevelRepair = true;
            } else {
                log.warn("Repair: strategy file strategies missing/not an array, will repair to empty");
                strategiesBroken = true;
            }

            // 4. versions must exist and be an array.
            JsonNode versionsNode = root.path("versions");
            List<StrategyVersion> versions = new ArrayList<>();
            boolean versionsBroken = false;
            if (versionsNode.isArray()) {
                boolean skipped = false;
                for (JsonNode n : versionsNode) {
                    try {
                        versions.add(mapper.treeToValue(n, StrategyVersion.class));
                    } catch (Exception skip) {
                        log.warn("Repair: dropped an unreadable version entry (id=?)");
                        skipped = true;
                    }
                }
                if (skipped) topLevelRepair = true;
            } else {
                log.warn("Repair: strategy file versions missing/not an array, will repair to empty");
                versionsBroken = true;
            }

            // Either broken array → full safe-degrade to empty (the global
            // repair then writes a legal empty snapshot back).
            if (strategiesBroken || versionsBroken) {
                return new ReadResult(new ArrayList<>(), new ArrayList<>(), true);
            }
            return new ReadResult(strategies, versions, topLevelRepair);
        } catch (Exception e) {
            // Corrupt / unreadable file: safe-degrade to the repair path, never
            // fail startup or any request. The exception is not logged verbatim
            // (it may echo file content); only the repair type is.
            log.warn("Repair: strategy file unreadable, will repair to empty");
            return new ReadResult(new ArrayList<>(), new ArrayList<>(), true);
        }
    }

    /**
     * Full per-entry legality for a strategy:
     * <ul>
     *   <li>id / workspaceId / name / createdByMemberId non-blank;</li>
     *   <li>name satisfies the server-side naming rules (trimmed, 1..64 chars,
     *       no control characters);</li>
     *   <li>status is ACTIVE or ARCHIVED;</li>
     *   <li>createdAt / updatedAt parse as ISO-8601 instants;</li>
     *   <li>archivedAt is null (ACTIVE) or a valid instant (ARCHIVED);
     *       an ACTIVE strategy may not carry an archive time and an ARCHIVED
     *       strategy MUST carry one.</li>
     * </ul>
     * {@code currentVersion} is NOT validated here: it is ALWAYS recomputed
     * from the valid version records after repair.
     */
    static boolean isValidStrategy(Strategy s) {
        if (s == null
                || s.id() == null || s.id().isBlank()
                || s.workspaceId() == null || s.workspaceId().isBlank()
                || s.name() == null || s.name().isBlank()
                || s.createdByMemberId() == null || s.createdByMemberId().isBlank()
                || s.createdAt() == null || s.createdAt().isBlank()
                || s.updatedAt() == null || s.updatedAt().isBlank()) {
            return false;
        }
        if (!isValidName(s.name())) return false;
        if (!Strategy.STATUS_ACTIVE.equals(s.status())
                && !Strategy.STATUS_ARCHIVED.equals(s.status())) {
            return false;
        }
        if (!isValidInstant(s.createdAt()) || !isValidInstant(s.updatedAt())) return false;
        if (Strategy.STATUS_ACTIVE.equals(s.status())) {
            return s.archivedAt() == null;
        }
        // ARCHIVED: archivedAt mandatory + valid.
        return s.archivedAt() != null && isValidInstant(s.archivedAt());
    }

    /**
     * Full per-entry legality for a version:
     * <ul>
     *   <li>id / strategyId / summary / createdByMemberId non-blank;</li>
     *   <li>versionNumber >= 1 (numbers are strictly increasing, never 0);</li>
     *   <li>summary satisfies the server-side rules (trimmed, 1..500 chars,
     *       no control characters);</li>
     *   <li>instruments satisfy the canonical deterministic form (1..20
     *       distinct ids, each 1..16 letters/digits, all UPPERCASE, no
     *       duplicates);</li>
     *   <li>timeframe is one of the whitelist values (canonical uppercase);</li>
     *   <li>entryRules / exitRules / riskNotes satisfy the rule rules (at most
     *       4000 chars, no control characters);</li>
     *   <li>createdAt parses as an ISO-8601 instant.</li>
     * </ul>
     */
    static boolean isValidVersion(StrategyVersion v) {
        if (v == null
                || v.id() == null || v.id().isBlank()
                || v.strategyId() == null || v.strategyId().isBlank()
                || v.summary() == null || v.summary().isBlank()
                || v.createdByMemberId() == null || v.createdByMemberId().isBlank()
                || v.createdAt() == null || v.createdAt().isBlank()) {
            return false;
        }
        if (v.versionNumber() < 1) return false;
        if (!isValidSummary(v.summary())) return false;
        if (!isValidInstruments(v.instruments())) return false;
        if (v.timeframe() == null || !TIMEFRAMES.contains(v.timeframe())) return false;
        if (!isValidRule(v.entryRules()) || !isValidRule(v.exitRules())
                || !isValidRule(v.riskNotes())) {
            return false;
        }
        return isValidInstant(v.createdAt());
    }

    /** The stored name must equal its trimmed form, be 1..{@value #MAX_NAME}
     *  chars, with no control characters. */
    static boolean isValidName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (!trimmed.equals(name) || trimmed.isEmpty()) return false;
        if (trimmed.length() > MAX_NAME) return false;
        return !hasControlCharacters(trimmed);
    }

    static boolean isValidSummary(String summary) {
        if (summary == null) return false;
        String trimmed = summary.trim();
        if (!trimmed.equals(summary) || trimmed.isEmpty()) return false;
        if (trimmed.length() > MAX_SUMMARY) return false;
        return !hasControlCharacters(trimmed);
    }

    static boolean isValidRule(String rule) {
        if (rule == null) return false;
        String trimmed = rule.trim();
        if (!trimmed.equals(rule)) return false;
        if (trimmed.length() > MAX_RULE_LENGTH) return false;
        return !hasControlCharacters(trimmed);
    }

    /** The stored instrument list must be canonical: 1..{@value #MAX_INSTRUMENTS}
     *  distinct ids, each 1..{@value #MAX_INSTRUMENT_LENGTH} letters/digits,
     *  all UPPERCASE, no duplicates. */
    static boolean isValidInstruments(List<String> instruments) {
        if (instruments == null || instruments.isEmpty()) return false;
        if (instruments.size() > MAX_INSTRUMENTS) return false;
        Set<String> seen = new HashSet<>();
        for (String item : instruments) {
            if (item == null || item.isEmpty() || item.length() > MAX_INSTRUMENT_LENGTH) {
                return false;
            }
            for (int i = 0; i < item.length(); i++) {
                char c = item.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                    return false; // letters must be UPPERCASE (canonical form)
                }
            }
            if (!seen.add(item)) return false; // no duplicates
        }
        return true;
    }

    private static boolean hasControlCharacters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    /** Whether the value parses as a valid ISO-8601 instant. */
    static boolean isValidInstant(String iso) {
        if (iso == null || iso.isBlank()) return false;
        try {
            Instant.parse(iso);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Atomic-ish write: temp file then move (fallback to non-atomic move). */
    private void persist(Snapshot snapshot) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(),
                    new StrategiesFile(SCHEMA, snapshot.strategies(), snapshot.versions()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Persistence failure must never break a request beyond a clear
            // error. The in-memory snapshot remains valid for this request.
            throw new StrategyApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    StrategyApiException.CODE_PERSIST_FAILED,
                    "策略保存失败：" + e.getMessage());
        }
    }

    /** The on-disk envelope. */
    record StrategiesFile(
            @JsonProperty("schema") String schema,
            @JsonProperty("strategies") List<Strategy> strategies,
            @JsonProperty("versions") List<StrategyVersion> versions) {
        @JsonCreator
        StrategiesFile {}
    }
}
