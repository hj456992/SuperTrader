package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MineruResultTest {
    @TempDir Path dir;

    @Test void mapsOutOfOrderBlocksToPhysicalPagesAndKeepsBlankPage() throws Exception {
        String layout="{\"pdf_info\":[{\"page_idx\":0},{\"page_idx\":1},{\"page_idx\":2}]}";
        String blocks="[{\"type\":\"text\",\"page_idx\":1,\"text\":\"second\",\"bbox\":[1,2,3,4]},"
                +"{\"type\":\"table\",\"page_idx\":0,\"table_body\":\"<table><tr><td>A</td></tr></table>\",\"table_caption\":[\"Caption\"]},"
                +"{\"type\":\"equation\",\"page_idx\":1,\"text\":\"E=mc^2\"}]";
        ObjectNode result=MineruResult.read(zip(Map.of("book_layout.json",layout,"book_content_list.json",blocks)),3);
        assertEquals("<table><tr><td>A</td></tr></table>\nCaption",result.path("pages").get(0).path("text").asText());
        assertEquals("second\nE=mc^2",result.path("pages").get(1).path("text").asText());
        assertEquals(2,result.path("pages").get(1).path("page").asInt());
        assertEquals(1,result.path("pages").get(1).path("blocks").get(0).path("bbox").get(0).asInt());
        assertEquals("",result.path("pages").get(2).path("text").asText());
        assertEquals(3,result.path("report").path("blankPageNumbers").get(0).asInt());
    }

    @Test void preservesTextPastFormerTruncationThreshold() throws Exception {
        String large="中文x".repeat(90_000);
        ObjectNode result=MineruResult.read(zip(Map.of("book_layout.json","{\"pdf_info\":[{\"page_idx\":0}]}",
                "book_content_list.json","[{\"type\":\"text\",\"page_idx\":0,\"text\":\""+large+"\"}]")),1);
        assertEquals(large,result.path("pages").get(0).path("text").asText());
        assertEquals(large.length(),result.path("report").path("charCount").asInt());
    }

    @Test void rejectsMissingOrDuplicateInventoryPages() throws Exception {
        for (String layout : new String[]{"{\"pdf_info\":[{\"page_idx\":0}]}",
                "{\"pdf_info\":[{\"page_idx\":0},{\"page_idx\":0}]}"}) {
            Path file=zip(Map.of("book_layout.json",layout,"book_content_list.json","[]"));
            assertThrows(Exception.class,()->MineruResult.read(file,2));
        }
    }

    @Test void rejectsUnknownMeaningfulBlockAndBadPageIndex() throws Exception {
        String layout="{\"pdf_info\":[{\"page_idx\":0}]}";
        for(String block : new String[]{"{\"type\":\"new_content\",\"page_idx\":0,\"text\":\"lost\"}",
                "{\"type\":\"text\",\"page_idx\":1,\"text\":\"wrong page\"}"}) {
            Path file=zip(Map.of("book_layout.json",layout,"book_content_list.json","["+block+"]"));
            assertThrows(Exception.class,()->MineruResult.read(file,1));
        }
    }

    @Test void rejectsUnsafeZipMemberEvenWhenNotRead() throws Exception {
        Path file=zip(Map.of("book_layout.json","{\"pdf_info\":[{\"page_idx\":0}]}",
                "book_content_list.json","[]","../escape.txt","malicious"));
        assertThrows(Exception.class,()->MineruResult.read(file,1));
    }

    @Test void reportsImageOnlyPagesWithoutPretendingTheyAreBlank() throws Exception {
        ObjectNode result=MineruResult.read(zip(Map.of("book_layout.json","{\"pdf_info\":[{\"page_idx\":0}]}",
                "book_content_list.json","[{\"type\":\"image\",\"page_idx\":0,\"img_path\":\"images/1.jpg\"}]")),1);
        assertEquals(1,result.path("report").path("imageOnlyPageNumbers").get(0).asInt());
        assertEquals(0,result.path("report").path("blankPageNumbers").size());
        assertEquals("images/1.jpg",result.path("pages").get(0).path("blocks").get(0).path("img_path").asText());
    }

    @Test void retainsDocumentedVlmCodeListChartAndAuxiliaryText() throws Exception {
        String content="[{\"type\":\"code\",\"page_idx\":0,\"code_body\":\"x = 1\",\"code_caption\":[\"Example\"]},"
                +"{\"type\":\"list\",\"page_idx\":0,\"list_items\":[\"first\",\"second\"]},"
                +"{\"type\":\"chart\",\"page_idx\":0,\"content\":\"| A | B |\",\"chart_footnote\":[\"source\"]},"
                +"{\"type\":\"page_number\",\"page_idx\":0,\"text\":\"9\"},"
                +"{\"type\":\"aside_text\",\"page_idx\":0,\"text\":\"aside\"}]";
        ObjectNode result=MineruResult.read(zip(Map.of("book_middle.json","{\"pdf_info\":[{\"page_idx\":0}]}",
                "book_content_list.json",content)),1);
        assertEquals("x = 1\nExample\nfirst\nsecond\n| A | B |\nsource\n9\naside",
                result.path("pages").get(0).path("text").asText());
    }

    @Test void recordsExplicitEmptyTextBlockWithoutDroppingItsEvidence() throws Exception {
        ObjectNode result=MineruResult.read(zip(Map.of("book_layout.json","{\"pdf_info\":[{\"page_idx\":0}]}",
                "book_content_list.json","[{\"type\":\"text\",\"text\":\"\",\"bbox\":[1,2,3,4],\"page_idx\":0}]")),1);
        assertEquals("", result.path("pages").get(0).path("text").asText());
        assertEquals("", result.path("pages").get(0).path("blocks").get(0).path("text").asText());
        assertEquals(1, result.path("report").path("emptyTextBlockCount").asInt());
        assertEquals(1, result.path("report").path("emptyTextPageNumbers").get(0).asInt());
    }

    @Test void stillRejectsMissingOrNonStringText() throws Exception {
        String layout="{\"pdf_info\":[{\"page_idx\":0}]}";
        for (String block : new String[]{"{\"type\":\"text\",\"page_idx\":0}",
                "{\"type\":\"text\",\"text\":42,\"page_idx\":0}"}) {
            assertThrows(IllegalArgumentException.class, () -> MineruResult.read(zip(Map.of(
                    "book_layout.json", layout, "book_content_list.json", "[" + block + "]")), 1));
        }
    }

    @Test void preservesActualRefTextSchemaAsPageTextAndBlock() throws Exception {
        ObjectNode result=MineruResult.read(zip(Map.of("book_layout.json","{\"pdf_info\":[{\"page_idx\":0}]}",
                "book_content_list.json","[{\"type\":\"ref_text\",\"text\":\"reference note\",\"bbox\":[1,2,3,4],\"page_idx\":0}]")),1);
        assertEquals("reference note", result.path("pages").get(0).path("text").asText());
        assertEquals("ref_text", result.path("pages").get(0).path("blocks").get(0).path("type").asText());
        assertEquals(1, result.path("report").path("blockTypes").path("ref_text").asInt());
    }

    @Test void usesPhysicalPreprocBlocksWhenContentListMergesAcrossPages() throws Exception {
        String layout="{\"pdf_info\":["
                +"{\"page_idx\":0,\"preproc_blocks\":[{\"type\":\"text\",\"index\":0,\"lines\":[{\"spans\":[{\"type\":\"text\",\"content\":\"on page one\"}]}]}],\"discarded_blocks\":[]},"
                +"{\"page_idx\":1,\"preproc_blocks\":[{\"type\":\"text\",\"index\":0,\"lines\":[{\"spans\":[{\"type\":\"text\",\"content\":\"on page two\"}]}]}],\"discarded_blocks\":[]}"
                +"]}";
        String merged="[{\"type\":\"text\",\"page_idx\":0,\"text\":\"on page one on page two\"},"
                +"{\"type\":\"text\",\"page_idx\":1,\"text\":\"\"}]";
        ObjectNode result=MineruResult.read(zip(Map.of("layout.json",layout,"book_content_list.json",merged)),2);
        assertEquals("on page one",result.path("pages").get(0).path("text").asText());
        assertEquals("on page two",result.path("pages").get(1).path("text").asText());
        assertEquals(1,result.path("pages").get(1).path("blocks").get(0).path("page_idx").asInt());
        assertEquals("layout.preproc_blocks",result.path("report").path("source").asText());
    }

    @Test void rejectsUnknownPhysicalSpanInsteadOfDroppingContent() throws Exception {
        String layout="{\"pdf_info\":[{\"page_idx\":0,\"preproc_blocks\":[{\"type\":\"text\",\"index\":0,"
                +"\"lines\":[{\"spans\":[{\"type\":\"novel\",\"content\":\"do not lose\"}]}]}],\"discarded_blocks\":[]}]}";
        assertThrows(IllegalArgumentException.class,()->MineruResult.read(zip(Map.of(
                "layout.json",layout,"book_content_list.json","[]")),1));
    }

    @Test void rejectsPartialOrMalformedPhysicalLayoutInsteadOfFallingBackToMergedText() throws Exception {
        String first="{\"page_idx\":0,\"preproc_blocks\":[],\"discarded_blocks\":[]}";
        String merged="[{\"type\":\"text\",\"page_idx\":0,\"text\":\"page one plus page two\"}]";
        for (String second : new String[]{"{\"page_idx\":1}", "{\"page_idx\":1,\"preproc_blocks\":null}"}) {
            String layout="{\"pdf_info\":["+first+","+second+"]}";
            assertThrows(IllegalArgumentException.class, () -> MineruResult.read(zip(Map.of(
                    "layout.json", layout, "book_content_list.json", merged)), 2));
        }
    }

    @Test void reportsPhysicalAssetOnlyBlockWithoutClaimingItsWordsWereRead() throws Exception {
        String layout="{\"pdf_info\":[{\"page_idx\":0,\"preproc_blocks\":[{\"type\":\"image\",\"index\":0,"
                +"\"blocks\":[{\"type\":\"image_body\",\"lines\":[{\"spans\":[{\"type\":\"image\",\"image_path\":\"figure.jpg\"}]}]}]}],"
                +"\"discarded_blocks\":[]}]}";
        ObjectNode result=MineruResult.read(zip(Map.of("layout.json",layout,"book_content_list.json","[]")),1);
        assertEquals("",result.path("pages").get(0).path("text").asText());
        assertEquals(1,result.path("report").path("assetOnlyBlockCount").asInt());
        assertEquals(1,result.path("report").path("assetOnlyPageNumbers").get(0).asInt());
        assertEquals(1,result.path("report").path("imageOnlyPageNumbers").get(0).asInt());
    }

    private Path zip(Map<String,String> members) throws Exception {
        Path file=Files.createTempFile(dir,"mineru-",".zip");
        try(var out=new ZipOutputStream(Files.newOutputStream(file),StandardCharsets.UTF_8)) {
            for(var entry : new LinkedHashMap<>(members).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
