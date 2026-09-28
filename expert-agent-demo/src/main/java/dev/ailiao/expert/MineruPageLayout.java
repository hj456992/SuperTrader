package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads text and evidence from physical-page layout before paragraph merging crosses page boundaries. */
final class MineruPageLayout {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> BLOCK_TYPES = Set.of("text", "title", "page_number", "header", "footer",
            "image", "image_body", "image_caption", "ref_text", "table", "table_body", "table_caption",
            "page_footnote", "interline_equation", "chart", "chart_body", "image_footnote", "code",
            "code_caption", "code_body", "chart_caption", "table_footnote");
    private static final Set<String> CONTENT_SPANS = Set.of("text", "inline_equation", "interline_equation");
    private static final Set<String> MEDIA_SPANS = Set.of("image", "chart");

    private MineruPageLayout() { }

    static boolean available(JsonNode inventory) {
        JsonNode pages = pageInventory(inventory);
        if (!pages.isArray()) return false;
        boolean supplied = false;
        for (JsonNode page : pages) supplied |= page.has("preproc_blocks");
        if (!supplied) return false;
        for (JsonNode page : pages)
            if (!page.path("preproc_blocks").isArray())
                throw new IllegalArgumentException("MinerU physical page layout is incomplete or invalid");
        return true;
    }

    static ObjectNode read(JsonNode inventory, int expectedPages, List<String> members) {
        JsonNode inventoryPages = pageInventory(inventory);
        ObjectNode result = JSON.createObjectNode();
        ArrayNode pages = result.putArray("pages");
        ObjectNode report = result.putObject("report");
        report.put("pageCount", expectedPages).put("source", "layout.preproc_blocks");
        ArrayNode blank = report.putArray("blankPageNumbers");
        ArrayNode imageOnly = report.putArray("imageOnlyPageNumbers");
        ArrayNode charCounts = report.putArray("pageCharCounts");
        report.putArray("emptyTextPageNumbers");
        report.put("emptyTextBlockCount", 0);
        ArrayNode assetOnlyPages = report.putArray("assetOnlyPageNumbers");
        int assetOnlyBlocks = 0;
        ArrayNode names = report.putArray("members");
        members.forEach(names::add);
        Map<String, Integer> typeCounts = new HashMap<>();
        long chars = 0;
        for (int pageIndex = 0; pageIndex < expectedPages; pageIndex++) {
            JsonNode source = inventoryPages.get(pageIndex);
            if (source.path("page_idx").asInt(-1) != pageIndex)
                throw new IllegalArgumentException("MinerU layout page order invalid");
            List<JsonNode> originals = new ArrayList<>();
            source.path("preproc_blocks").forEach(originals::add);
            JsonNode discarded = source.path("discarded_blocks");
            if (!discarded.isMissingNode() && !discarded.isArray())
                throw new IllegalArgumentException("Invalid MinerU discarded blocks");
            discarded.forEach(originals::add);
            originals.sort(Comparator.comparingInt(b -> b.path("index").asInt(Integer.MAX_VALUE)));
            ObjectNode page = pages.addObject();
            page.put("page", pageIndex + 1);
            ArrayNode blocks = page.putArray("blocks");
            List<String> lines = new ArrayList<>();
            boolean[] media = {false};
            boolean assetOnlyPage = false;
            for (JsonNode original : originals) {
                if (!original.isObject()) throw new IllegalArgumentException("Invalid MinerU layout block");
                String type = original.path("type").asText();
                if (!BLOCK_TYPES.contains(type)) throw new IllegalArgumentException("Unknown MinerU layout block type: " + type);
                ObjectNode block = original.deepCopy();
                block.put("page_idx", pageIndex);
                blocks.add(block);
                typeCounts.merge(type, 1, Integer::sum);
                List<String> blockText = new ArrayList<>();
                boolean[] blockMedia = {false};
                collect(block, blockText, blockMedia);
                lines.addAll(blockText);
                media[0] |= blockMedia[0];
                if (blockMedia[0] && blockText.isEmpty()) {
                    assetOnlyBlocks++;
                    assetOnlyPage = true;
                }
            }
            if (assetOnlyPage) assetOnlyPages.add(pageIndex + 1);
            String pageText = String.join("\n", lines);
            page.put("text", pageText);
            charCounts.add(pageText.length());
            chars += pageText.length();
            if (pageText.isBlank()) {
                if (media[0]) imageOnly.add(pageIndex + 1);
                else blank.add(pageIndex + 1);
            }
        }
        report.put("charCount", chars);
        report.put("assetOnlyBlockCount", assetOnlyBlocks);
        ObjectNode counts = report.putObject("blockTypes");
        typeCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return result;
    }

    private static JsonNode pageInventory(JsonNode root) {
        return root.isArray() ? root : root.has("pdf_info") ? root.path("pdf_info") : root.path("pages");
    }

    private static void collect(JsonNode block, List<String> text, boolean[] media) {
        JsonNode children = block.path("blocks");
        if (!children.isMissingNode()) {
            if (!children.isArray()) throw new IllegalArgumentException("Invalid MinerU nested blocks");
            for (JsonNode child : children) {
                String type = child.path("type").asText();
                if (!BLOCK_TYPES.contains(type)) throw new IllegalArgumentException("Unknown MinerU layout block type: " + type);
                collect(child, text, media);
            }
        }
        JsonNode lines = block.path("lines");
        if (!lines.isMissingNode()) {
            if (!lines.isArray()) throw new IllegalArgumentException("Invalid MinerU layout lines");
            for (JsonNode line : lines) {
                JsonNode spans = line.path("spans");
                if (!spans.isArray()) throw new IllegalArgumentException("Invalid MinerU layout spans");
                for (JsonNode span : spans) {
                    String type = span.path("type").asText();
                    if (CONTENT_SPANS.contains(type) || type.equals("table")) {
                        JsonNode value = span.path(type.equals("table") ? "html" : "content");
                        if (!value.isTextual()) throw new IllegalArgumentException("Invalid MinerU layout span content");
                        if (!value.asText().isEmpty()) text.add(value.asText());
                        if (type.equals("table") && span.path("image_path").isTextual()) media[0] = true;
                    } else if (MEDIA_SPANS.contains(type)) {
                        if (!span.path("image_path").isTextual())
                            throw new IllegalArgumentException("Invalid MinerU layout image path");
                        media[0] = true;
                    } else throw new IllegalArgumentException("Unknown MinerU layout span type: " + type);
                }
            }
        }
        if (children.isMissingNode() && lines.isMissingNode())
            throw new IllegalArgumentException("MinerU layout block lacks content");
    }
}
