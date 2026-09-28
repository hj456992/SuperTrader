package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MineruResultPartsTest {
    @TempDir Path dir;

    @Test void offsetsPhysicalPagesAndBlocksAcrossBoundary() throws Exception {
        Path first = zip("[{\"page_idx\":0},{\"page_idx\":1}]",
                "[{\"page_idx\":1,\"type\":\"text\",\"text\":\"last on first\"}]");
        Path second = zip("[{\"page_idx\":0},{\"page_idx\":1}]",
                "[{\"page_idx\":0,\"type\":\"image\",\"img_path\":\"img/1.png\"},"
                + "{\"page_idx\":1,\"type\":\"text\",\"text\":\"next\"}]");
        ObjectNode result = MineruResult.readParts(List.of(first, second), List.of(2, 2));
        assertEquals(4, result.path("report").path("pageCount").asInt());
        assertEquals(1, result.path("report").path("blankPageNumbers").get(0).asInt());
        assertEquals(3, result.path("report").path("imageOnlyPageNumbers").get(0).asInt());
        assertEquals(3, result.path("pages").get(2).path("page").asInt());
        assertEquals(2, result.path("pages").get(2).path("blocks").get(0).path("page_idx").asInt());
        assertEquals(3, result.path("pages").get(3).path("blocks").get(0).path("page_idx").asInt());
        assertEquals(17, result.path("report").path("charCount").asInt());
        assertEquals(2, result.path("report").path("blockTypes").path("text").asInt());
        assertEquals(1, result.path("report").path("blockTypes").path("image").asInt());
        assertEquals(1, result.path("report").path("parts").get(0).path("startPage").asInt());
        assertEquals(2, result.path("report").path("parts").get(0).path("endPage").asInt());
        assertEquals(3, result.path("report").path("parts").get(1).path("startPage").asInt());
        assertEquals(4, result.path("report").path("parts").get(1).path("endPage").asInt());
        assertEquals(13, result.path("report").path("pageCharCounts").get(1).asInt());
        assertEquals(4, result.path("report").path("pageCharCounts").get(3).asInt());
    }

    @Test void rejectsMissingInventoryPageAndInvalidManifest() throws Exception {
        Path missing = zip("[{\"page_idx\":0}]", "[]");
        assertThrows(IllegalArgumentException.class,
                () -> MineruResult.readParts(List.of(missing), List.of(2)));
        assertThrows(IllegalArgumentException.class,
                () -> MineruResult.readParts(List.of(missing), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> MineruResult.readParts(List.of(missing), List.of(0)));
    }

    @Test void offsetsAndSumsEmptyTextAnomaliesAcrossParts() throws Exception {
        Path first = zip("[{\"page_idx\":0}]", "[]");
        Path second = zip("[{\"page_idx\":0}]",
                "[{\"type\":\"text\",\"text\":\"\",\"bbox\":[1,2,3,4],\"page_idx\":0}]");
        ObjectNode result = MineruResult.readParts(List.of(first, second), List.of(1, 1));
        assertEquals(1, result.path("report").path("emptyTextBlockCount").asInt());
        assertEquals(2, result.path("report").path("emptyTextPageNumbers").get(0).asInt());
    }

    @Test void rejectsCumulativeCompressedZipLimitBeforeParsing() throws Exception {
        Path a = dir.resolve("large-a.zip"), b = dir.resolve("large-b.zip");
        try (var channel = Files.newByteChannel(a, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(101L * 1024 * 1024 - 1); channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        try (var channel = Files.newByteChannel(b, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(101L * 1024 * 1024 - 1); channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MineruResult.readParts(List.of(a, b), List.of(1, 1)));
    }

    private Path zip(String inventory, String blocks) throws Exception {
        Path path = Files.createTempFile(dir, "part-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : Map.of("book_layout.json", "{\"pdf_info\":" + inventory + "}",
                    "book_content_list.json", blocks).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return path;
    }
}
