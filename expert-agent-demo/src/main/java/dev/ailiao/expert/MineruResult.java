package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Converts MinerU's page-indexed content list into physical PDF pages without truncation. */
final class MineruResult {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_ZIP_BYTES = 200L * 1024 * 1024;
    private static final long MAX_JSON_BYTES = 100L * 1024 * 1024;
    private static final Set<String> TEXT_TYPES = Set.of(
            "text", "title", "list", "equation", "header", "footer", "page_header", "page_footer",
            "page_footnote", "footnote", "image_caption", "image_footnote", "table_caption", "table_footnote",
            "algorithm", "chart_caption", "chart_footnote", "page_number", "aside_text", "page_aside_text", "ref_text");

    private MineruResult() { }

    static ObjectNode read(Path zip, int expectedPages) throws Exception {
        return read(zip, expectedPages, new long[]{0});
    }

    /** Merge complete ordered parts onto the original physical PDF page numbering. */
    static ObjectNode readParts(List<Path> zips, List<Integer> counts) throws Exception {
        if (zips == null || counts == null || zips.isEmpty() || zips.size() != counts.size())
            throw new IllegalArgumentException("Invalid MinerU part manifest");
        long zipBytes = 0;
        int pageCount = 0;
        for (int i = 0; i < zips.size(); i++) {
            Integer count = counts.get(i);
            if (zips.get(i) == null || count == null || count < 1 || count > 200 || pageCount + count > 600)
                throw new IllegalArgumentException("Invalid MinerU part manifest");
            pageCount += count;
            zipBytes += Files.size(zips.get(i));
            if (zipBytes > MAX_ZIP_BYTES) throw new IllegalArgumentException("MinerU ZIPs exceed 200 MiB");
        }
        ObjectNode merged = JSON.createObjectNode();
        ArrayNode pages = merged.putArray("pages");
        ObjectNode report = merged.putObject("report");
        report.put("pageCount", pageCount);
        ArrayNode blank = report.putArray("blankPageNumbers");
        ArrayNode imageOnly = report.putArray("imageOnlyPageNumbers");
        ArrayNode charCounts = report.putArray("pageCharCounts");
        ArrayNode emptyTextPages = report.putArray("emptyTextPageNumbers");
        ArrayNode assetOnlyPages = report.putArray("assetOnlyPageNumbers");
        ArrayNode memberNames = report.putArray("members");
        ArrayNode provenance = report.putArray("parts");
        ObjectNode blockTypes = report.putObject("blockTypes");
        long totalChars = 0;
        int emptyTextBlocks = 0;
        int assetOnlyBlocks = 0;
        boolean allLayout = true;
        int offset = 0;
        long[] jsonRead = {0};
        for (int i = 0; i < zips.size(); i++) {
            int count = counts.get(i);
            ObjectNode part = read(zips.get(i), count, jsonRead);
            ObjectNode partReport = (ObjectNode) part.path("report");
            allLayout &= "layout.preproc_blocks".equals(partReport.path("source").asText());
            ObjectNode origin = provenance.addObject();
            origin.put("index", i).put("startPage", offset + 1).put("endPage", offset + count);
            origin.set("report", partReport.deepCopy());
            for (JsonNode localPage : part.path("pages")) {
                ObjectNode page = localPage.deepCopy();
                page.put("page", page.path("page").asInt() + offset);
                for (JsonNode block : page.path("blocks"))
                    ((ObjectNode) block).put("page_idx", block.path("page_idx").asInt() + offset);
                pages.add(page);
            }
            for (JsonNode n : partReport.path("blankPageNumbers")) blank.add(n.asInt() + offset);
            for (JsonNode n : partReport.path("imageOnlyPageNumbers")) imageOnly.add(n.asInt() + offset);
            for (JsonNode n : partReport.path("pageCharCounts")) charCounts.add(n.asInt());
            for (JsonNode n : partReport.path("emptyTextPageNumbers")) emptyTextPages.add(n.asInt() + offset);
            emptyTextBlocks += partReport.path("emptyTextBlockCount").asInt();
            for (JsonNode n : partReport.path("assetOnlyPageNumbers")) assetOnlyPages.add(n.asInt() + offset);
            assetOnlyBlocks += partReport.path("assetOnlyBlockCount").asInt();
            for (JsonNode name : partReport.path("members"))
                memberNames.add("part-%03d/%s".formatted(i + 1, name.asText()));
            partReport.path("blockTypes").fields().forEachRemaining(entry ->
                    blockTypes.put(entry.getKey(), blockTypes.path(entry.getKey()).asInt() + entry.getValue().asInt()));
            totalChars += partReport.path("charCount").asLong();
            offset += count;
        }
        report.put("charCount", totalChars);
        report.put("emptyTextBlockCount", emptyTextBlocks);
        report.put("assetOnlyBlockCount", assetOnlyBlocks);
        report.put("source", allLayout ? "layout.preproc_blocks" : "content_list");
        return merged;
    }

    private static ObjectNode read(Path zip, int expectedPages, long[] totalRead) throws Exception {
        if (expectedPages < 1) throw new IllegalArgumentException("Invalid expected PDF page count");
        if (Files.size(zip) > MAX_ZIP_BYTES) throw new IllegalArgumentException("MinerU ZIP exceeds 200 MiB");
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry content = null, inventory = null;
            List<String> members = new ArrayList<>();
            Set<String> names = new HashSet<>();
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                validateName(name);
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate MinerU ZIP member: " + name);
                members.add(name);
                if (entry.isDirectory()) continue;
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.endsWith("_content_list.json") || base.equals("content_list.json")) {
                    if (content != null) throw new IllegalArgumentException("Multiple MinerU content lists");
                    content = entry;
                }
                if (base.endsWith("_layout.json") || base.equals("layout.json")
                        || base.endsWith("_middle.json") || base.equals("middle_json.json")) {
                    // The legacy middle JSON's pdf_info is a page inventory too.
                    if (inventory != null) throw new IllegalArgumentException("Multiple MinerU page inventories");
                    inventory = entry;
                }
            }
            if (content == null || inventory == null)
                throw new IllegalArgumentException("MinerU ZIP lacks page inventory or content list");
            JsonNode pageInventory = JSON.readTree(readBounded(archive, inventory, totalRead));
            JsonNode contentList = JSON.readTree(readBounded(archive, content, totalRead));
            validateInventory(pageInventory, expectedPages);
            if (!contentList.isArray()) throw new IllegalArgumentException("MinerU content list is not an array");
            if (MineruPageLayout.available(pageInventory))
                return MineruPageLayout.read(pageInventory, expectedPages, members);

            List<ArrayNode> blocks = new ArrayList<>(expectedPages);
            List<List<String>> text = new ArrayList<>(expectedPages);
            Map<String,Integer> typeCounts = new HashMap<>();
            boolean[] image = new boolean[expectedPages];
            boolean[] emptyText = new boolean[expectedPages];
            int emptyTextBlockCount = 0;
            for (int i = 0; i < expectedPages; i++) {
                blocks.add(JSON.createArrayNode());
                text.add(new ArrayList<>());
            }
            for (JsonNode block : contentList) {
                if (!block.isObject()) throw new IllegalArgumentException("Invalid MinerU content block");
                int page = pageIndex(block.path("page_idx"), expectedPages);
                String type = requiredString(block.path("type"), "content type");
                List<String> parts = blockText(block, type);
                if (type.equals("text") && block.path("text").isTextual()
                        && block.path("text").asText().isEmpty()) {
                    emptyText[page] = true;
                    emptyTextBlockCount++;
                }
                blocks.get(page).add(block.deepCopy());
                text.get(page).addAll(parts);
                if (type.equals("image") || type.equals("chart") || block.hasNonNull("img_path")) image[page] = true;
                typeCounts.merge(type, 1, Integer::sum);
            }

            ObjectNode result = JSON.createObjectNode();
            ArrayNode pages = result.putArray("pages");
            ObjectNode report = result.putObject("report");
            report.put("pageCount", expectedPages);
            ArrayNode blank = report.putArray("blankPageNumbers");
            ArrayNode imageOnly = report.putArray("imageOnlyPageNumbers");
            ArrayNode charCounts = report.putArray("pageCharCounts");
            ArrayNode emptyTextPageNumbers = report.putArray("emptyTextPageNumbers");
            long charCount = 0;
            for (int i = 0; i < expectedPages; i++) {
                String pageText = String.join("\n", text.get(i));
                ObjectNode page = pages.addObject();
                page.put("page", i + 1);
                page.put("text", pageText);
                page.set("blocks", blocks.get(i));
                charCounts.add(pageText.length());
                charCount += pageText.length();
                if (emptyText[i]) emptyTextPageNumbers.add(i + 1);
                if (pageText.isBlank()) {
                    if (image[i]) imageOnly.add(i + 1);
                    else blank.add(i + 1);
                }
            }
            report.put("charCount", charCount);
            report.put("emptyTextBlockCount", emptyTextBlockCount);
            ObjectNode counts = report.putObject("blockTypes");
            typeCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> counts.put(e.getKey(), e.getValue()));
            ArrayNode memberNames = report.putArray("members");
            members.forEach(memberNames::add);
            return result;
        }
    }

    private static void validateName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0
                || name.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("Unsafe MinerU ZIP member name");
        for (String part : name.split("/", -1))
            if (part.equals("..") || part.equals(".")) throw new IllegalArgumentException("Unsafe MinerU ZIP member path");
    }

    private static byte[] readBounded(ZipFile archive, ZipEntry entry, long[] totalRead) throws Exception {
        try (InputStream in = archive.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                totalRead[0] += n;
                if (totalRead[0] > MAX_JSON_BYTES) throw new IllegalArgumentException("MinerU JSON exceeds 100 MiB");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void validateInventory(JsonNode root, int expectedPages) {
        JsonNode pages = root.isArray() ? root : root.has("pdf_info") ? root.path("pdf_info") : root.path("pages");
        if (!pages.isArray() || pages.size() != expectedPages)
            throw new IllegalArgumentException("MinerU page inventory does not match PDF page count");
        boolean[] seen = new boolean[expectedPages];
        for (JsonNode page : pages) {
            int idx = pageIndex(page.path("page_idx"), expectedPages);
            if (seen[idx]) throw new IllegalArgumentException("Duplicate MinerU inventory page " + idx);
            seen[idx] = true;
        }
    }

    private static int pageIndex(JsonNode node, int expectedPages) {
        if (!node.isIntegralNumber() || !node.canConvertToInt())
            throw new IllegalArgumentException("MinerU page_idx is missing or invalid");
        int idx = node.asInt();
        if (idx < 0 || idx >= expectedPages)
            throw new IllegalArgumentException("MinerU page_idx outside PDF: " + idx);
        return idx;
    }

    private static String requiredString(JsonNode node, String label) {
        if (!node.isTextual() || node.asText().isBlank())
            throw new IllegalArgumentException("Missing MinerU " + label);
        return node.asText();
    }

    private static List<String> blockText(JsonNode block, String type) {
        List<String> parts = new ArrayList<>();
        if (TEXT_TYPES.contains(type)) {
            addText(parts, block.path("text"), "text");
            if (type.equals("list")) addText(parts, block.path("list_items"), "list_items");
            if (parts.isEmpty() && !(type.equals("text") && block.path("text").isTextual()
                    && block.path("text").asText().isEmpty()))
                throw new IllegalArgumentException("MinerU " + type + " block lacks text");
        } else if (type.equals("code")) {
            addText(parts, block.path("code_body"), "code_body");
            addText(parts, block.path("code_caption"), "code_caption");
            addText(parts, block.path("code_footnote"), "code_footnote");
            if (parts.isEmpty()) throw new IllegalArgumentException("MinerU code block lacks text");
        } else if (type.equals("table")) {
            addText(parts, block.path("table_body"), "table_body");
            addText(parts, block.path("text"), "text");
            addText(parts, block.path("table_caption"), "table_caption");
            addText(parts, block.path("table_footnote"), "table_footnote");
            if (parts.isEmpty() && !block.hasNonNull("img_path"))
                throw new IllegalArgumentException("MinerU table lacks content");
        } else if (type.equals("image") || type.equals("chart")) {
            addText(parts, block.path("content"), "content");
            addText(parts, block.path("text"), "text");
            addText(parts, block.path("img_caption"), "img_caption");
            addText(parts, block.path("image_caption"), "image_caption");
            addText(parts, block.path("image_footnote"), "image_footnote");
            addText(parts, block.path("chart_caption"), "chart_caption");
            addText(parts, block.path("chart_footnote"), "chart_footnote");
            if (parts.isEmpty() && !block.hasNonNull("img_path"))
                throw new IllegalArgumentException("MinerU image lacks asset or caption");
        } else {
            throw new IllegalArgumentException("Unknown MinerU content type: " + type);
        }
        return parts;
    }

    private static void addText(List<String> parts, JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) return;
        if (node.isTextual()) {
            if (!node.asText().isEmpty()) parts.add(node.asText());
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) addText(parts, item, field);
                else if (item.isObject()) {
                    if (item.has("text")) addText(parts, item.path("text"), field);
                    else if (item.has("content")) addText(parts, item.path("content"), field);
                    else throw new IllegalArgumentException("Unknown MinerU " + field + " item");
                } else throw new IllegalArgumentException("Unknown MinerU " + field + " item");
            }
        } else throw new IllegalArgumentException("Invalid MinerU " + field);
    }
}
