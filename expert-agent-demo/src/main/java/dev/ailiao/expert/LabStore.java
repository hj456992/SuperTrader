package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Demo 的本地不可变版本快照；所有写入先落盘再替换内存状态。 */
final class LabStore {
    private final Path root;
    private ObjectNode data;
    /** 打开隔离数据目录。@param root 私有目录 */
    LabStore(Path root) throws Exception {
        this.root = root.toAbsolutePath();
        Files.createDirectories(this.root);
        Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwx------"));
        Path path = this.root.resolve("state.json");
        data = Files.exists(path) ? (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(path)) : Json.object();
        data.withArray("documents"); data.withArray("experts"); data.withArray("conversations");
    }
    /** 原子替换，失败时保留原状态。@param next 新状态 */
    private void commit(ObjectNode next) throws Exception {
        Path temporary = Files.createTempFile(root, "state-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.write(temporary, Json.MAPPER.writeValueAsBytes(next));
            Files.move(temporary, root.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            data = next;
        } finally { Files.deleteIfExists(temporary); }
    }
    /** 查询固定身份，找不到时明确失败。@param values 对象数组 @param id 身份 */
    private static ObjectNode find(JsonNode values, String id) {
        for (JsonNode value : values) { if (value.path("id").asText().equals(id)) { return (ObjectNode) value; } }
        throw new IllegalArgumentException("所选资料、专家或版本不存在");
    }
    /** 保存新资料版本，已有资料名称保持一致。@param title 新资料名称 @param documentId 已有身份或空 @param pages 解析页 @param filename 原文件名 @param pdf 原文件 */
    synchronized ObjectNode importDocument(String title, String documentId, ArrayNode pages, String filename, byte[] pdf) throws Exception {
        return importDocument(title, documentId, pages, filename, pdf, Json.object().put("provider", "local"), "");
    }
    synchronized ObjectNode importDocument(String title, String documentId, ArrayNode pages, String filename, byte[] pdf, ObjectNode parser, String importId) throws Exception {
        ObjectNode prior = importResult(importId);
        if (prior != null) { return prior; }
        if (title.isBlank() || title.length() > 100) { throw new IllegalArgumentException("资料名称须为 1–100 字"); }
        ObjectNode next = data.deepCopy();
        ObjectNode doc;
        if (documentId.isBlank()) {
            doc = Json.object().put("id", Json.id()).put("title", title).put("createdAt", Instant.now().toString());
            doc.set("versions", Json.array()); next.withArray("documents").add(doc);
        } else { doc = find(next.path("documents"), documentId); }
        String versionId = Json.id();
        ArrayNode chunks = Knowledge.chunks(doc.path("id").asText(), versionId, doc.path("title").asText(), pages);
        int chars = pages.findValuesAsText("text").stream().mapToInt(String::length).sum();
        if (chunks.isEmpty() || pages.size() > 600) { throw new IllegalArgumentException("PDF 没有可读文字或超过 Demo 限额"); }
        int blank = (int) pages.findValuesAsText("text").stream().filter(String::isBlank).count();
        ObjectNode version = Json.object().put("id", versionId).put("number", doc.path("versions").size() + 1)
            .put("filename", filename).put("pageCount", pages.size()).put("charCount", chars).put("blankPages", blank)
            .put("createdAt", Instant.now().toString()).put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)));
        version.set("chunks", chunks); version.set("parser", parser.deepCopy());
        if (!importId.isBlank()) { version.put("importId", importId); }
        doc.withArray("versions").add(version);
        Path original = root.resolve(versionId + ".pdf");
        Files.write(original, pdf, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(original, PosixFilePermissions.fromString("rw-------"));
        try { commit(next); } catch (Exception e) { Files.deleteIfExists(original); throw e; }
        return Json.object().put("documentId", doc.path("id").asText()).put("versionId", versionId).put("blankPages", blank);
    }
    /** Recover committed imports even if the process stopped before updating the import journal. */
    synchronized ObjectNode importResult(String importId) {
        if (importId.isBlank()) { return null; }
        for (JsonNode doc : data.path("documents")) { for (JsonNode v : doc.path("versions")) {
            if (importId.equals(v.path("importId").asText())) {
                return Json.object().put("documentId", doc.path("id").asText()).put("versionId", v.path("id").asText()).put("blankPages", v.path("blankPages").asInt());
            }
        } }
        return null;
    }
    synchronized ObjectNode documentVersion(String versionId) {
        for (JsonNode doc : data.path("documents")) { for (JsonNode version : doc.path("versions")) {
            if (versionId.equals(version.path("id").asText())) { return ((ObjectNode) version).deepCopy(); }
        } }
        throw new IllegalArgumentException("资料版本不存在");
    }
    synchronized Path original(String versionId) {
        documentVersion(versionId);
        return root.resolve(versionId + ".pdf");
    }
    /** 仅返回页面需要的元数据，完整原文按需读取。 */
    synchronized ObjectNode state() {
        ObjectNode result = data.deepCopy();
        result.remove("conversations");
        for (JsonNode doc : result.path("documents")) {
            for (JsonNode version : doc.path("versions")) {
                ((ObjectNode) version).put("chunkCount", version.path("chunks").size());
                ((ObjectNode) version).remove("chunks");
            }
        }
        return result;
    }
    /** 解析选择清单并强制一份资料只能选一个版本。@param selections 资料/版本配对 */
    synchronized ArrayNode selectedChunks(JsonNode selections) {
        if (!selections.isArray() || selections.isEmpty() || selections.size() > 8) { throw new IllegalArgumentException("请选择 1–8 份资料"); }
        Set<String> documents = new HashSet<>();
        ArrayNode result = Json.array();
        int chars = 0;
        for (JsonNode selection : selections) {
            String id = Json.required(selection, "documentId", 80);
            if (!documents.add(id)) { throw new IllegalArgumentException("同一资料只能选择一个版本"); }
            ObjectNode doc = find(data.path("documents"), id);
            ObjectNode version = find(doc.path("versions"), Json.required(selection, "versionId", 80));
            for (JsonNode c : version.path("chunks")) { result.add(c.deepCopy()); chars += c.path("text").asText().length(); }
        }
        if (chars > 250000) { throw new IllegalArgumentException("所选资料合计超过 25 万字，请减少资料数量"); }
        return result;
    }
    /** 保存完整草稿，不替换当前启用指针。@param expertId 专家身份或空 @param spec 已校验配置 */
    synchronized ObjectNode saveExpert(String expertId, ObjectNode spec) throws Exception {
        selectedChunks(spec.path("selections"));
        ObjectNode next = data.deepCopy();
        ObjectNode expert;
        if (expertId.isBlank()) {
            expert = Json.object().put("id", Json.id()).put("activeVersionId", "");
            expert.set("versions", Json.array()); next.withArray("experts").add(expert);
        } else { expert = find(next.path("experts"), expertId); }
        ObjectNode version = spec.deepCopy();
        version.put("id", Json.id()).put("number", expert.path("versions").size() + 1).put("tested", false).put("createdAt", Instant.now().toString());
        expert.withArray("versions").add(version); expert.put("name", version.path("name").asText());
        commit(next);
        return Json.object().put("expertId", expert.path("id").asText()).put("versionId", version.path("id").asText());
    }
    /** 返回独立快照，避免调用方改变历史配置。@param expertId 专家 @param versionId 版本 */
    synchronized ObjectNode expertVersion(String expertId, String versionId) {
        return find(find(data.path("experts"), expertId).path("versions"), versionId).deepCopy();
    }
    /** 成功试聊后记录资格。@param expertId 专家 @param versionId 固定版本 */
    synchronized void markTested(String expertId, String versionId) throws Exception {
        ObjectNode next = data.deepCopy();
        find(find(next.path("experts"), expertId).path("versions"), versionId).put("tested", true);
        commit(next);
    }
    /** 只启用经过成功试聊的完整版本。@param expertId 专家 @param versionId 固定版本 */
    synchronized void activate(String expertId, String versionId) throws Exception {
        ObjectNode next = data.deepCopy();
        ObjectNode expert = find(next.path("experts"), expertId);
        if (!find(expert.path("versions"), versionId).path("tested").asBoolean()) { throw new IllegalArgumentException("请先成功试聊该版本，再启用"); }
        expert.put("activeVersionId", versionId); commit(next);
    }
    /** 查阅已上传原文，供本地管理员核对出处。@param id 片段身份 */
    synchronized ObjectNode passage(String id) {
        for (JsonNode doc : data.path("documents")) { for (JsonNode v : doc.path("versions")) { for (JsonNode c : v.path("chunks")) {
            if (c.path("id").asText().equals(id)) { return ((ObjectNode) c).deepCopy(); }
        } } }
        throw new IllegalArgumentException("原文不存在");
    }
    /** 读取绑定当前专家版本的聊天，拒绝跨版本续聊。@param id 会话或空 @param expertId 专家 @param versionId 版本 */
    synchronized ObjectNode conversation(String id, String expertId, String versionId) {
        if (id.isBlank()) {
            ObjectNode c = Json.object().put("id", Json.id()).put("expertId", expertId).put("versionId", versionId);
            c.set("messages", Json.array()); return c;
        }
        ObjectNode c = find(data.path("conversations"), id);
        if (!expertId.equals(c.path("expertId").asText()) || !versionId.equals(c.path("versionId").asText())) { throw new IllegalArgumentException("切换专家版本后请开始新试聊"); }
        return c.deepCopy();
    }
    /** 成功结果和试聊标记一起原子落盘，失败不留下半轮消息。@param conversation 已完成会话 */
    synchronized void saveConversation(ObjectNode conversation) throws Exception {
        ObjectNode next = data.deepCopy();
        ArrayNode all = next.withArray("conversations");
        for (int i = all.size() - 1; i >= 0; i--) { if (all.get(i).path("id").equals(conversation.path("id"))) { all.remove(i); } }
        all.add(conversation.deepCopy());
        JsonNode last = conversation.path("messages").get(conversation.path("messages").size() - 1);
        if (last != null && last.path("verifiedTrial").asBoolean()) {
            find(find(next.path("experts"), conversation.path("expertId").asText()).path("versions"), conversation.path("versionId").asText()).put("tested", true);
        }
        commit(next);
    }
}
