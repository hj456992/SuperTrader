package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class PdfSplitTest {
    private static final String PYTHON = "/Users/hou/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3";
    @TempDir Path dir;

    @Test void splitsAtTwoHundredPagesWithoutChangingSource() throws Exception {
        Path source = makePdf(false);
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
        Path parts = dir.resolve("private-parts");
        ArrayNode manifest = new PdfImport(PYTHON, Path.of("extract_pdf.py"), dir.resolve("tmp"))
                .split(source, parts, new Jobs.Job("import", "test"));
        assertEquals(2, manifest.size());
        assertEquals(1, manifest.get(0).path("startPage").asInt());
        assertEquals(200, manifest.get(0).path("endPage").asInt());
        assertEquals(200, manifest.get(0).path("pageCount").asInt());
        assertEquals("part-001.pdf", manifest.get(0).path("filename").asText());
        assertEquals(201, manifest.get(1).path("startPage").asInt());
        assertEquals(201, manifest.get(1).path("endPage").asInt());
        assertEquals(1, manifest.get(1).path("pageCount").asInt());
        assertEquals("part-002.pdf", manifest.get(1).path("filename").asText());
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        assertPdf(parts.resolve("part-001.pdf"), 200, 100, 299);
        assertPdf(parts.resolve("part-002.pdf"), 1, 300, 300);
    }

    @Test void emptyPasswordEncryptionStaysOnOriginalAndPartsAreReadable() throws Exception {
        Path source = makePdf(true);
        byte[] before = Files.readAllBytes(source);
        Path parts = dir.resolve("private-parts");
        new PdfImport(PYTHON, Path.of("extract_pdf.py"), dir.resolve("tmp"))
                .split(source, parts, new Jobs.Job("import", "test"));
        assertArrayEquals(before, Files.readAllBytes(source));
        assertPdf(parts.resolve("part-001.pdf"), 200, 100, 299);
        assertPdf(parts.resolve("part-002.pdf"), 1, 300, 300);
    }

    private Path makePdf(boolean encrypted) throws Exception {
        Path source = dir.resolve("source.pdf");
        String code = "from pypdf import PdfWriter; import sys; w=PdfWriter(); "
                + "[w.add_blank_page(width=100+i,height=400+i) for i in range(201)]; "
                + (encrypted ? "w.encrypt(''); " : "") + "w.write(sys.argv[1])";
        Process p = new ProcessBuilder(PYTHON, "-c", code, source.toString()).start();
        assertEquals(0, p.waitFor());
        return source;
    }

    private void assertPdf(Path path, int count, int firstWidth, int lastWidth) throws Exception {
        String code = "from pypdf import PdfReader; import sys; r=PdfReader(sys.argv[1]); "
                + "assert not r.is_encrypted; assert len(r.pages)==int(sys.argv[2]); "
                + "assert int(r.pages[0].mediabox.width)==int(sys.argv[3]); "
                + "assert int(r.pages[-1].mediabox.width)==int(sys.argv[4])";
        Process p = new ProcessBuilder(PYTHON, "-c", code, path.toString(),
                Integer.toString(count), Integer.toString(firstWidth), Integer.toString(lastWidth)).start();
        assertEquals(0, p.waitFor(), new String(p.getErrorStream().readAllBytes()));
    }
}
