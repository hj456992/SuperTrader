package com.supertrader.demo.taskcenter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of the CONTROLLED local RAG (Module 9): citations, deterministic
 * offline index, EVIDENCE_MISSING and the security boundary (traversal /
 * absolute paths / symlink escapes / hidden files / env vars).
 */
class RagKnowledgeServiceTest {

    private static KnowledgeService knowledge;

    @BeforeAll
    static void setUp() {
        // The committed rebuild/knowledge/ base (read-only).
        Path root = Path.of(System.getProperty("user.dir")).getParent().resolve("knowledge");
        knowledge = new KnowledgeService(root.toString());
    }

    @Test
    void searchReturnsCitationsWithFullMetadata() {
        var hits = knowledge.search("双均线交叉 窗口");
        assertFalse(hits.isEmpty(), "the knowledge base must answer");
        for (KnowledgeService.KnowledgeHit h : hits) {
            assertNotNull(h.sourceId());
            assertNotNull(h.title());
            assertNotNull(h.relativePath());
            assertFalse(h.relativePath().startsWith("/"), "relative path only");
            assertNotNull(h.snippet());
            assertEquals(64, h.contentHash().length());
            assertTrue(h.score() > 0);
        }
        // The hit comes from the knowledge root only.
        for (KnowledgeService.KnowledgeHit h : hits) {
            assertTrue(h.relativePath().endsWith(".md")
                    || h.relativePath().endsWith(".json")
                    || h.relativePath().endsWith(".txt"));
        }
    }

    @Test
    void searchIsDeterministicAndOffline() {
        var first = knowledge.search("SMA_CROSS 参数");
        var second = knowledge.search("SMA_CROSS 参数");
        assertEquals(first.stream().map(KnowledgeService.KnowledgeHit::sourceId).toList(),
                second.stream().map(KnowledgeService.KnowledgeHit::sourceId).toList());
        assertEquals(first.get(0).snippet(), second.get(0).snippet());
    }

    @Test
    void noKnowledgeIsEvidenceMissingNeverInvented() {
        // Pure ASCII junk: no term and no CJK bigram can match any document.
        assertTrue(knowledge.search("qqzzxwvmnop").isEmpty(),
                "EVIDENCE_MISSING: an empty result, never fabricated content");
    }

    @Test
    void traversalAndAbsolutePathsAreRejected() {
        for (String bad : List.of("../../etc/passwd", "../credentials",
                "/etc/passwd", "/Users/me/secret.md", "${HOME}", "$HOME",
                "env:HOME", "a\\b", "query..", "..%2F..")) {
            var e = assertThrows(TaskCenterApiException.class,
                    () -> knowledge.search(bad));
            assertEquals(400, e.status().value());
            assertEquals("INVALID_CONTENT", e.code());
        }
    }

    @Test
    void blankOrOverlongQueriesAreRejected() {
        assertEquals(400, assertThrows(TaskCenterApiException.class,
                () -> knowledge.search("  ")).status().value());
        assertEquals(400, assertThrows(TaskCenterApiException.class,
                () -> knowledge.search("x".repeat(201))).status().value());
    }

    @Test
    void hiddenFilesAndSymlinkEscapesAreExcluded() throws Exception {
        Path tmp = Files.createTempDirectory("simnow-kb-test-");
        Path kb = tmp.resolve("kb");
        Files.createDirectories(kb);
        Files.writeString(kb.resolve("ok.md"), "双均线交叉 正常知识");
        Files.writeString(kb.resolve(".hidden.md"), "双均线交叉 隐藏凭证");
        Files.writeString(kb.resolve("credentials.json"), "双均线交叉 秘密");
        // A symlink pointing outside the knowledge root (skipped on macOS/Linux).
        Path outside = tmp.resolve("outside.txt");
        Files.writeString(outside, "双均线交叉 越界内容");
        try {
            Files.createSymbolicLink(kb.resolve("escape.md"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // symlinks unsupported on this FS — the test still covers hidden files
        }
        KnowledgeService kbService = new KnowledgeService(kb.toString());
        var hits = kbService.search("双均线交叉");
        assertFalse(hits.isEmpty());
        for (KnowledgeService.KnowledgeHit h : hits) {
            assertTrue(h.relativePath().equals("ok.md"),
                    "hidden / credential-shaped / symlink files must not be indexed, got "
                            + h.relativePath());
        }
        assertTrue(hits.stream().noneMatch(h -> h.relativePath().contains("escape")));
    }

    @Test
    void resultsCarryContentHashesForTraceability() throws Exception {
        Path tmp = Files.createTempDirectory("simnow-kb-hash-");
        Files.writeString(tmp.resolve("doc.md"), "# 文档\n双均线交叉内容");
        KnowledgeService kb = new KnowledgeService(tmp.toString());
        var hits = kb.search("双均线");
        assertEquals(1, hits.size());
        var hit = hits.get(0);
        // The content hash equals sha256 of the raw file bytes.
        String raw = Files.readString(tmp.resolve("doc.md"));
        assertEquals(KnowledgeService.sha256(raw), hit.contentHash());
        assertEquals("文档", hit.title());
        assertEquals("doc.md", hit.relativePath());
    }

    @Test
    void missingKnowledgeDirYieldsEmptyEvidence() throws Exception {
        Path tmp = Files.createTempDirectory("simnow-kb-missing-");
        KnowledgeService kb = new KnowledgeService(tmp.resolve("nope").toString());
        assertTrue(kb.search("均线").isEmpty());
    }
}
