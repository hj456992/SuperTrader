package com.supertrader.demo.taskcenter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The CONTROLLED local RAG (Module 9, V1). It serves ONLY the explicitly
 * included non-sensitive documents under {@code app.taskcenter.knowledge-dir}
 * (default {@code ../knowledge} relative to the backend working dir, i.e.
 * {@code rebuild/knowledge/}) — never the network, a cloud service or any
 * external connector.
 *
 * <p>Security boundary (all enforced, unit-tested):
 * <ul>
 *   <li>ONLY files under the knowledge root are ever read;</li>
 *   <li>{@code ../} traversal, absolute paths, symlink escapes, hidden
 *       (dot-prefixed) files, environment-variable references and
 *       {@code .run/} credential/account data are REJECTED;</li>
 *   <li>whitelisted extensions: .md / .json / .txt;</li>
 *   <li>every result carries sourceId + title + relative path + snippet +
 *       content hash; answers citing knowledge return the citation;</li>
 *   <li>when no knowledge matches, EVIDENCE_MISSING is returned — the service
 *       NEVER fabricates content;</li>
 *   <li>the index is rebuilt deterministically on demand (local word-term
 *       search, no external vector database) and every test is offline and
 *       repeatable.</li>
 * </ul>
 *
 * <p>RAG results are research evidence ONLY: they can never be the basis of
 * an automatic approval and never produce backtest metrics.
 */
@Component
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    static final Set<String> ALLOWED_EXTENSIONS = Set.of(".md", ".json", ".txt");
    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_RESULTS = 3;
    static final int SNIPPET_CHARS = 160;

    private final Path knowledgeRoot;

    public KnowledgeService(@Value("${app.taskcenter.knowledge-dir}") String knowledgeDir) {
        this.knowledgeRoot = Path.of(knowledgeDir).toAbsolutePath().normalize();
    }

    /** Visible for tests. */
    Path knowledgeRoot() {
        return knowledgeRoot;
    }

    /** One indexed knowledge document (deterministic). */
    public record KnowledgeDoc(String sourceId, String title, String relativePath,
                               String content, String contentHash) {}

    /** One search result. */
    public record KnowledgeHit(String sourceId, String title, String relativePath,
                               String snippet, String contentHash, int score) {}

    /**
     * Search the local knowledge base. Returns up to {@value #MAX_RESULTS}
     * hits, or an EMPTY list when nothing matches (the caller then surfaces
     * EVIDENCE_MISSING). A malformed / forbidden query is rejected
     * fail-closed.
     */
    public synchronized List<KnowledgeHit> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            throw TaskCenterApiException.invalidBody("检索关键词不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw TaskCenterApiException.invalidBody(
                    "检索关键词过长（最多 " + MAX_QUERY_LENGTH + " 个字符）");
        }
        if (query.contains("..") || query.startsWith("/")
                || query.startsWith("${") || query.startsWith("$")
                || query.contains("\\") || query.contains("\u0000")
                || query.toLowerCase(Locale.ROOT).contains("env:")) {
            throw TaskCenterApiException.invalidContent(
                    "检索关键词包含路径穿越/绝对路径/环境变量等禁止形态");
        }

        Map<String, Integer> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of(); // nothing searchable → EVIDENCE_MISSING (never invented)
        }
        List<KnowledgeHit> hits = new ArrayList<>();
        for (KnowledgeDoc doc : index().values()) {
            int score = score(doc, terms);
            if (score > 0) {
                hits.add(new KnowledgeHit(doc.sourceId(), doc.title(), doc.relativePath(),
                        snippet(doc.content(), query), doc.contentHash(), score));
            }
        }
        hits.sort(Comparator.comparingInt(KnowledgeHit::score).reversed()
                .thenComparing(KnowledgeHit::sourceId));
        return hits.size() > MAX_RESULTS ? hits.subList(0, MAX_RESULTS) : hits;
    }

    // ------------------------------------------------------------------ //
    // Deterministic local index (rebuilt on demand, no external store)
    // ------------------------------------------------------------------ //

    private volatile Map<String, KnowledgeDoc> cachedIndex;

    private Map<String, KnowledgeDoc> index() {
        Map<String, KnowledgeDoc> idx = cachedIndex;
        if (idx != null) return idx;
        idx = rebuildIndex();
        cachedIndex = idx;
        return idx;
    }

    /** Rebuild the index deterministically by walking the knowledge root. */
    private Map<String, KnowledgeDoc> rebuildIndex() {
        Map<String, KnowledgeDoc> out = new LinkedHashMap<>();
        if (knowledgeRoot == null || !Files.isDirectory(knowledgeRoot)) {
            log.warn("KnowledgeService: knowledge dir missing or not a directory: {}",
                    knowledgeRoot);
            return out;
        }
        try (var stream = Files.walk(knowledgeRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isHiddenPath(knowledgeRoot.relativize(p)))
                    .filter(p -> !isBannedFileName(p.getFileName().toString()))
                    .filter(p -> isAllowedExtension(p.getFileName().toString()))
                    .filter(p -> isRealFile(knowledgeRoot, p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        KnowledgeDoc doc = readDoc(knowledgeRoot, p);
                        if (doc != null) out.put(doc.sourceId(), doc);
                    });
        } catch (IOException e) {
            log.warn("KnowledgeService: cannot walk knowledge dir ({}); index stays empty",
                    e.getClass().getSimpleName());
        }
        return out;
    }

    private KnowledgeDoc readDoc(Path root, Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String relative = root.relativize(file).toString();
            String sourceId = sha256(relative);
            // Title: first heading line of a markdown doc, else the file name.
            String title = firstHeading(content);
            if (title == null) title = file.getFileName().toString();
            return new KnowledgeDoc(sourceId, title, relative, content,
                    sha256(content));
        } catch (Exception e) {
            log.warn("KnowledgeService: ignored unreadable document {} ({})",
                    file.getFileName(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static String firstHeading(String content) {
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("# ") || t.startsWith("## ")) {
                return t.replaceFirst("^#+\\s*", "").trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    // Security boundary (fail-closed)
    // ------------------------------------------------------------------ //

    private static boolean isHiddenPath(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") && !name.equals(".")) return true;
        }
        return false;
    }

    private static boolean isAllowedExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** Credential-shaped file names are NEVER indexed (defense in depth). */
    private static boolean isBannedFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String token : List.of("password", "credential", "credentials",
                "secret", "token", "authcode", "auth-code", "privatekey",
                "private-key", "apikey", "api-key", "investor", "brokerid",
                "appid", ".env")) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    /** Reject any file that is not a REAL regular file inside the root
     *  (symlinks escaping the root are refused). */
    private static boolean isRealFile(Path root, Path file) {
        try {
            Path real = file.toRealPath();
            if (!Files.isRegularFile(real)) return false;
            return real.startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ //
    // Deterministic word-term scoring
    // ------------------------------------------------------------------ //

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    /**
     * Deterministic offline tokenizer: ASCII words + CJK character bigrams
     * (Chinese text has no spaces, so character bigrams give a stable,
     * testable term model). Never touches the network or any vector store.
     */
    static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> out = new HashMap<>();
        if (text == null || text.isBlank()) return out;
        String lower = text.toLowerCase(Locale.ROOT);
        var m = WORD.matcher(lower);
        while (m.find()) {
            String w = m.group();
            if (isCjkRun(w)) continue; // handled by the bigram pass below
            out.merge(w, 1, Integer::sum);
        }
        for (int i = 0; i < lower.length() - 1; i++) {
            if (isCjkChar(lower.charAt(i)) && isCjkChar(lower.charAt(i + 1))) {
                out.merge(lower.substring(i, i + 2), 1, Integer::sum);
            }
        }
        return out;
    }

    private static boolean isCjkRun(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isCjkChar(s.charAt(i))) return false;
        }
        return s.length() > 1;
    }

    private static boolean isCjkChar(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static int score(KnowledgeDoc doc, Map<String, Integer> terms) {
        String lower = doc.content().toLowerCase(Locale.ROOT);
        int score = 0;
        for (Map.Entry<String, Integer> e : terms.entrySet()) {
            int count = countOccurrences(lower, e.getKey());
            if (count == 0) {
                // A multi-term query must match at least one term; scoring is
                // per term so a partial match still scores (ranked), but a
                // document matching NOTHING gets 0 and is excluded.
                continue;
            }
            score += count * e.getValue();
        }
        return score;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** A deterministic snippet around the first query-term occurrence. */
    static String snippet(String content, String query) {
        String plain = content.replaceAll("\\s+", " ").trim();
        if (plain.isEmpty()) return "";
        int idx = -1;
        for (String term : tokenize(query).keySet()) {
            int i = plain.toLowerCase(Locale.ROOT).indexOf(term);
            if (i >= 0) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return plain.length() <= SNIPPET_CHARS
                    ? plain : plain.substring(0, SNIPPET_CHARS) + "…";
        }
        int start = Math.max(0, idx - SNIPPET_CHARS / 3);
        int end = Math.min(plain.length(), start + SNIPPET_CHARS);
        return (start > 0 ? "…" : "") + plain.substring(start, end)
                + (end < plain.length() ? "…" : "");
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute hash", e);
        }
    }
}
