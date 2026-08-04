package com.supertrader.demo.taskcenter;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * The local dataset registry of the Module 9 Backtest Runner (V1).
 *
 * <p>It reads ONLY the explicitly registered local dataset directory
 * ({@code app.taskcenter.datasets-dir}, default {@code ../datasets} relative
 * to the backend working dir, i.e. {@code rebuild/datasets}) — every dataset
 * file must carry the strict {@code backtest-dataset.v1} protocol and the
 * SAMPLE_ONLY flag; anything else is ignored (fail-closed: an unreadable or
 * invalid dataset is simply not registered). The built-in datasets are
 * explicitly labeled 「内置确定性验收样本，非真实行情，不构成投资建议」 and are
 * NEVER presented as real market data.
 *
 * <p>The catalog is derived from the actual files (single source of truth);
 * it is rebuilt deterministically on demand and NEVER writes to the datasets
 * directory. Loading the catalog never triggers diagnosis, probe, SimNow,
 * Agent or any external call. A dataset is "stale" when its {@code staleAfter}
 * is set and has passed — stale datasets are excluded from coverage so the
 * Runner / Validator fail closed on expired data.
 */
@Component
public class DatasetCatalog {

    private static final Logger log = LoggerFactory.getLogger(DatasetCatalog.class);

    static final String SCHEMA = "backtest-dataset.v1";
    static final String SCHEMA_V2 = "backtest-dataset.v2";
    static final Set<String> TIMEFRAMES = Set.of(
            "TICK", "1M", "5M", "15M", "30M", "1H", "1D");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path datasetsDir;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DatasetCatalog(@Value("${app.taskcenter.datasets-dir}") String datasetsDir) {
        this(datasetsDir, Clock.systemUTC());
    }

    public DatasetCatalog(String datasetsDir, Clock clock) {
        this.datasetsDir = Path.of(datasetsDir);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Visible for tests. */
    Path datasetsDir() {
        return datasetsDir;
    }

    /** The registered dataset descriptors (without bars), sorted by id. */
    public synchronized List<DatasetDescriptor> descriptors() {
        return catalog().stream().map(d -> DatasetDescriptor.from(d, clock)).toList();
    }

    /** The datasets that cover the given instrument + timeframe and are NOT
     *  stale (fail-closed on expired data). Empty means DATA_UNAVAILABLE. */
    public synchronized List<DatasetDescriptor> coverage(String instrument, String timeframe) {
        String tf = timeframe == null ? "" : timeframe.trim().toUpperCase(Locale.ROOT);
        String inst = instrument == null ? "" : instrument.trim().toUpperCase(Locale.ROOT);
        return descriptors().stream()
                .filter(d -> d.instrument().equals(inst) && d.timeframe().equals(tf))
                .filter(d -> !d.stale())
                .toList();
    }

    /** Load the FULL dataset (with bars) by id; null when not registered. */
    public synchronized Dataset load(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) return null;
        for (Dataset d : catalog()) {
            if (d.datasetId().equals(datasetId)) return d;
        }
        return null;
    }

    boolean isStale(Dataset dataset) {
        return dataset == null || dataset.stale(clock);
    }

    // ------------------------------------------------------------------ //
    // Catalog (derived from the datasets directory, deterministic)
    // ------------------------------------------------------------------ //

    private volatile List<Dataset> cached;

    /** TEST-ONLY: drop the cached catalog so newly written fixture datasets
     *  are picked up (tests write extra datasets into their scratch dir). */
    void invalidateCacheForTests() {
        cached = null;
    }

    private List<Dataset> catalog() {
        List<Dataset> c = cached;
        if (c != null) return c;
        c = rebuildCatalog();
        cached = c;
        return c;
    }

    private List<Dataset> rebuildCatalog() {
        List<Dataset> out = new ArrayList<>();
        if (datasetsDir == null || !Files.isDirectory(datasetsDir)) {
            log.warn("DatasetCatalog: datasets dir missing or not a directory: {}", datasetsDir);
            return out;
        }
        try (var stream = Files.list(datasetsDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path p : files) {
                Dataset d = readDataset(p);
                if (d != null) out.add(d);
            }
        } catch (IOException e) {
            log.warn("DatasetCatalog: cannot list datasets dir {} ({}); catalog stays empty",
                    datasetsDir, e.getClass().getSimpleName());
        }
        return out;
    }

    private Dataset readDataset(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            if (bytes.length == 0) return null;
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) return null;
            JsonNode schemaNode = root.path("schema");
            if (!schemaNode.isTextual()
                    || !(SCHEMA.equals(schemaNode.asText())
                    || SCHEMA_V2.equals(schemaNode.asText()))) return null;
            boolean v2 = SCHEMA_V2.equals(schemaNode.asText());
            Set<String> allowedRoot = v2 ? Set.of("schema", "datasetId", "instrument",
                    "timeframe", "periodStart", "periodEnd", "generatedAt", "staleAfter",
                    "sampleOutStatus", "description", "bars", "dataVersion", "contentHash",
                    "asOf", "availableAt") : Set.of("schema", "datasetId", "instrument",
                    "timeframe", "periodStart", "periodEnd", "generatedAt", "staleAfter",
                    "sampleOutStatus", "description", "bars");
            if (hasUnknown(root, allowedRoot)) return null;
            JsonNode barsNode = root.path("bars");
            if (!barsNode.isArray() || barsNode.isEmpty()) return null;
            List<Bar> bars = new ArrayList<>();
            for (JsonNode n : barsNode) {
                if (!n.isObject() || hasUnknown(n, Set.of("ts", "open", "high", "low",
                        "close", "volume"))) return null;
                Bar bar = mapper.treeToValue(n, Bar.class);
                if (bar == null || !bar.isValid()) return null; // strict: one bad bar drops the dataset
                bars.add(bar);
            }
            String computedHash = semanticHash(root);
            String declaredHash = text(root, "contentHash");
            if (v2 && (text(root, "dataVersion") == null || declaredHash == null
                    || !declaredHash.equals(computedHash)
                    || text(root, "asOf") == null || text(root, "availableAt") == null)) {
                return null;
            }
            Dataset d = new Dataset(
                    text(root, "datasetId"), text(root, "instrument"),
                    text(root, "timeframe"), text(root, "periodStart"),
                    text(root, "periodEnd"), text(root, "generatedAt"),
                    text(root, "staleAfter"), text(root, "sampleOutStatus"),
                    text(root, "description"), bars, v2 ? text(root, "dataVersion") : "legacy-v1",
                    v2 ? declaredHash : computedHash, text(root, "asOf"),
                    text(root, "availableAt"));
            if (!d.isValid()) return null;
            return d;
        } catch (Exception e) {
            log.warn("DatasetCatalog: ignored unreadable dataset file {} ({})",
                    p.getFileName(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static boolean hasUnknown(JsonNode object, Set<String> allowed) {
        var names = object.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return true;
        return false;
    }

    /** Stable semantic SHA-256; object-key order and the declared hash field are ignored. */
    private static String semanticHash(JsonNode root) throws Exception {
        Object normalized = normalize(root, true);
        byte[] bytes = new ObjectMapper().writeValueAsBytes(normalized);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder("sha256:");
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static Object normalize(JsonNode node, boolean root) {
        if (node.isObject()) {
            TreeMap<String, Object> out = new TreeMap<>();
            node.fields().forEachRemaining(e -> {
                if (!(root && "contentHash".equals(e.getKey()))) {
                    out.put(e.getKey(), normalize(e.getValue(), false));
                }
            });
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(normalize(n, false)));
            return out;
        }
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNull()) return null;
        return node.asText();
    }

    /** A single OHLCV bar. */
    public record Bar(
            @JsonProperty("ts") String ts,
            @JsonProperty("open") double open,
            @JsonProperty("high") double high,
            @JsonProperty("low") double low,
            @JsonProperty("close") double close,
            @JsonProperty("volume") long volume) {
        @JsonCreator
        public Bar {}
        boolean isValid() {
            return isValidInstant(ts)
                    && open > 0 && high > 0 && low > 0 && close > 0
                    && high >= low && high >= open && high >= close
                    && low <= open && low <= close;
        }
    }

    /** A full registered dataset (with bars). */
    public record Dataset(
            @JsonProperty("datasetId") String datasetId,
            @JsonProperty("instrument") String instrument,
            @JsonProperty("timeframe") String timeframe,
            @JsonProperty("periodStart") String periodStart,
            @JsonProperty("periodEnd") String periodEnd,
            @JsonProperty("generatedAt") String generatedAt,
            @JsonProperty("staleAfter") String staleAfter,
            @JsonProperty("sampleOutStatus") String sampleOutStatus,
            @JsonProperty("description") String description,
            @JsonProperty("bars") List<Bar> bars,
            @JsonProperty("dataVersion") String dataVersion,
            @JsonProperty("contentHash") String contentHash,
            @JsonProperty("asOf") String asOf,
            @JsonProperty("availableAt") String availableAt) {
        @JsonCreator
        public Dataset {}
        boolean isValid() {
            if (datasetId == null || datasetId.isBlank()
                    || instrument == null || instrument.isBlank()
                    || timeframe == null || !TIMEFRAMES.contains(timeframe)
                    || periodStart == null || periodEnd == null
                    || bars == null || bars.isEmpty()) {
                return false;
            }
            if (!BacktestRun.SAMPLE_OUT_SAMPLE_ONLY.equals(sampleOutStatus)) {
                // V1 registers ONLY clearly-labeled deterministic acceptance
                // samples; anything else is ignored (fail-closed).
                return false;
            }
            if (!isValidInstant(periodStart) || !isValidInstant(periodEnd)
                    || !isValidInstant(generatedAt)
                    || (staleAfter != null && !isValidInstant(staleAfter))
                    || (asOf != null && !isValidInstant(asOf))
                    || (availableAt != null && !isValidInstant(availableAt))
                    || dataVersion == null || dataVersion.isBlank()
                    || contentHash == null || contentHash.isBlank()) return false;
            Instant start = Instant.parse(periodStart);
            Instant end = Instant.parse(periodEnd);
            if (end.isBefore(start)) return false;
            Instant previous = null;
            for (Bar bar : bars) {
                Instant ts = Instant.parse(bar.ts());
                if (ts.isBefore(start) || ts.isAfter(end)
                        || (previous != null && !ts.isAfter(previous))) return false;
                previous = ts;
            }
            return true;
        }
        public boolean stale() {
            return stale(Clock.systemUTC());
        }
        boolean stale(Clock clock) {
            if (staleAfter == null || staleAfter.isBlank()) return false;
            try {
                return !clock.instant().isBefore(Instant.parse(staleAfter));
            } catch (Exception e) {
                return true; // unparseable expiry → fail-closed stale
            }
        }
    }

    /** The catalog view without bars (safe to return to callers). */
    public record DatasetDescriptor(
            String datasetId, String instrument, String timeframe,
            String periodStart, String periodEnd, int inputBars,
            String sampleOutStatus, String description, boolean stale,
            String dataVersion, String contentHash) {
        static DatasetDescriptor from(Dataset d, Clock clock) {
            return new DatasetDescriptor(d.datasetId(), d.instrument(), d.timeframe(),
                    d.periodStart(), d.periodEnd(), d.bars().size(),
                    d.sampleOutStatus(), d.description(), d.stale(clock),
                    d.dataVersion(), d.contentHash());
        }
    }

    private static boolean isValidInstant(String iso) {
        if (iso == null || iso.isBlank()) return false;
        try {
            Instant.parse(iso);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
