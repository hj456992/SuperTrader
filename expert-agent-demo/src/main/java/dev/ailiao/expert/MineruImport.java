package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Durable journal for cloud imports only; all other work keeps the existing Jobs lifecycle. */
final class MineruImport {
    private final Path root;
    private final LabStore store;
    private final Jobs jobs;
    private final MineruClient client;
    private final PdfImport pdf;
    private final Map<String, ObjectNode> records = new LinkedHashMap<>();
    private final Set<String> splitReservations = new HashSet<>();
    MineruImport(Path data, LabStore store, Jobs jobs, MineruClient client, PdfImport pdf) throws Exception {
        this.root = data.resolve("imports"); this.store = store; this.jobs = jobs; this.client = client; this.pdf = pdf;
        Files.createDirectories(root); privateDirectory(root);
        try (var folders = Files.list(root)) {
            for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
                Path file = folder.resolve("state.json");
                if (!Files.isRegularFile(file)) { continue; }
                ObjectNode record = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(file));
                String id = record.path("id").asText();
                if (!id.equals(folder.getFileName().toString())) { throw new IllegalStateException("导入记录身份不一致"); }
                recoverSavedBatches(record, folder);
                records.put(id, record);
                ObjectNode result = store.importResult(id);
                if (result != null) { record.put("status", "completed"); record.set("result", result); }
                else if (record.path("status").asText().equals("running")) { record.put("status", "paused").put("message", "服务重启，点击继续查询原任务；不会重新提交云解析"); }
                record.remove("jobId"); save(record);
            }
        }
    }
    synchronized ArrayNode list() {
        ArrayNode result = Json.array(); records.values().forEach(record -> result.add(publicRecord(record))); return result;
    }
    synchronized ObjectNode get(String id) { return publicRecord(record(id)); }
    private ObjectNode publicRecord(ObjectNode record) {
        ObjectNode result = record.deepCopy();
        result.put("canResume", canResume(record));
        result.put("canSplit", canSplit(record));
        return result;
    }
    private boolean canResume(ObjectNode value) {
        String status = value.path("status").asText();
        if (status.equals("running") || status.equals("completed") || value.path("remoteFailed").asBoolean() || value.path("pageLimitExceeded").asBoolean()) { return false; }
        if (value.has("parts")) {
            for (JsonNode part : value.path("parts")) {
                if (part.path("submissionAttempted").asBoolean() && part.path("batchId").asText().isBlank()) { return false; }
            }
            return true;
        }
        return !value.path("batchId").asText().isBlank() || !value.path("submissionAttempted").asBoolean();
    }
    private boolean canSplit(ObjectNode value) {
        return !Set.of("running", "completed").contains(value.path("status").asText())
            && (value.path("pageLimitExceeded").asBoolean() || (value.path("remoteFailed").asBoolean() && value.path("error").asText().contains("200")))
            && value.path("pageCount").asInt() > 200 && !value.has("splitSourceId")
            && records.values().stream().noneMatch(r -> value.path("id").asText().equals(r.path("splitSourceId").asText()));
    }
    /** Explicit user choice starts a new journal; the original rejected attempt remains evidence. */
    ObjectNode split(String sourceId) throws Exception {
        ObjectNode source;
        synchronized (this) {
            for (ObjectNode r : records.values()) {
                if (sourceId.equals(r.path("splitSourceId").asText())) {
                    return Json.object().put("importId", r.path("id").asText()).put("jobId", r.path("jobId").asText());
                }
            }
            source = record(sourceId).deepCopy();
            if (!canSplit(source)) { throw new IllegalArgumentException("只有超过200页限制的暂停或失败整书任务可选择拆分"); }
            if (!splitReservations.add(sourceId)) { throw new IllegalArgumentException("拆分任务正在创建，请稍后查看原任务"); }
        }
        try {
            return submit(source.path("title").asText(), source.path("documentId").asText(), source.path("filename").asText(),
                Files.readAllBytes(root.resolve(sourceId).resolve("original.pdf")), sourceId);
        } finally { synchronized (this) { splitReservations.remove(sourceId); } }
    }
    private ObjectNode record(String id) {
        ObjectNode value = records.get(id);
        if (value == null) { throw new IllegalArgumentException("导入任务不存在"); }
        return value;
    }
    ObjectNode submit(String title, String documentId, String filename, byte[] raw) throws Exception {
        return submit(title, documentId, filename, raw, "");
    }
    private ObjectNode submit(String title, String documentId, String filename, byte[] raw, String splitSourceId) throws Exception {
        client.checkConfigured();
        if (title.isBlank() || title.length() > 100) { throw new IllegalArgumentException("资料名称须为 1–100 字"); }
        if (!documentId.isBlank()) {
            boolean exists = false;
            for (JsonNode d : store.state().path("documents")) { if (documentId.equals(d.path("id").asText())) { exists = true; } }
            if (!exists) { throw new IllegalArgumentException("所选资料不存在"); }
        }
        if (raw.length > 20 * 1024 * 1024 || raw.length < 5 || !new String(raw, 0, Math.min(1024, raw.length), StandardCharsets.ISO_8859_1).stripLeading().startsWith("%PDF-")) { throw new IllegalArgumentException("请上传不超过 20MB 的有效 PDF"); }
        String id = Json.id(); Path folder = root.resolve(id); Files.createDirectory(folder); privateDirectory(folder);
        Path original = folder.resolve("original.pdf"); Files.write(original, raw, StandardOpenOption.CREATE_NEW); privateFile(original);
        ObjectNode value = Json.object().put("id", id).put("title", title).put("documentId", documentId).put("filename", filename)
            .put("status", "prepared").put("createdAt", Instant.now().toString()).put("bytes", raw.length)
            .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        if (!splitSourceId.isBlank()) { value.put("splitSourceId", splitSourceId).put("splitApprovedAt", Instant.now().toString()); }
        value.set("parameters", MineruClient.parameters("original.pdf", id)); value.set("timeline", Json.array());
        synchronized (this) { records.put(id, value); save(value); }
        try { return launch(id); }
        catch (Exception e) { synchronized (this) { records.remove(id); } Files.deleteIfExists(folder.resolve("state.json")); Files.deleteIfExists(original); Files.deleteIfExists(folder); throw e; }
    }
    ObjectNode resume(String id) throws Exception {
        synchronized (this) {
            if (!canResume(record(id))) { throw new IllegalArgumentException("该任务当前不能继续；已完成任务不会重复入库"); }
        }
        client.checkConfigured(); return launch(id);
    }
    private ObjectNode launch(String id) throws Exception {
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        Jobs.Job job = jobs.start("import", "ocr", work -> { ready.await(); return run(id, work); });
        try { synchronized (this) { ObjectNode value = record(id); value.put("status", "running").put("jobId", job.id).put("message", "准备云解析任务"); value.remove(List.of("error", "errorDetails")); save(value); } }
        catch (Exception e) { job.cancel(); throw e; }
        finally { ready.countDown(); }
        return Json.object().put("importId", id).put("jobId", job.id);
    }
    /** Caller must not hold the journal lock while acquiring a Job's commit/cancel lock. */
    boolean cancelJob(String jobId) throws Exception {
        String id = null;
        synchronized (this) { for (ObjectNode r : records.values()) { if (jobId.equals(r.path("jobId").asText())) { id = r.path("id").asText(); break; } } }
        if (id == null) { return false; }
        Jobs.Job job = jobs.get(jobId); job.cancel();
        if (!job.snapshot().path("status").asText().equals("completed")) {
            updateIfCurrent(id, job, r -> r.put("status", "cancelled").put("message", "已停止本地处理；云端可能继续解析，可手动继续原任务"));
        }
        return true;
    }
    private ObjectNode run(String id, Jobs.Job job) throws Exception {
        try {
            Path folder = root.resolve(id), original = folder.resolve("original.pdf");
            ObjectNode initial;
            synchronized (this) { initial = currentRecord(id, job).deepCopy(); }
            ObjectNode prior = store.importResult(id);
            if (prior != null) { return job.commit(() -> finish(id, job, prior)); }
            int expected = initial.path("pageCount").asInt();
            if (expected == 0) {
                stage(id, job, "inspecting", "核对原 PDF 页数，原文件保持不变");
                ObjectNode info = pdf.inspect(original, job); expected = info.path("pageCount").asInt();
                int pages = expected;
                updateCurrent(id, job, r -> r.put("pageCount", pages).set("pdfInfo", info));
            }
            if (expected > 200 && !initial.has("splitSourceId") && initial.path("batchId").asText().isBlank() && !Files.isRegularFile(folder.resolve("result.zip"))) {
                updateCurrent(id, job, r -> r.put("pageLimitExceeded", true));
                throw new IllegalArgumentException("原文件共" + expected + "页，超过 MinerU 单次200页限制；请选择分卷解析后合并，原文件保持不变");
            }
            ObjectNode parsed;
            ObjectNode provenance = Json.object().put("provider", "mineru").put("model", "vlm");
            if (initial.has("splitSourceId")) {
                if (!initial.has("parts")) {
                    stage(id, job, "splitting", "制作每份最多200页的临时副本；原件不变");
                    ArrayNode parts = pdf.split(original, folder, job);
                    for (JsonNode part : parts) {
                        ObjectNode item = (ObjectNode) part;
                        byte[] bytes = Files.readAllBytes(folder.resolve(item.path("filename").asText()));
                        item.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                        item.set("parameters", MineruClient.parameters(item.path("filename").asText(), id + "-" + item.path("index").asInt()));
                    }
                    updateCurrent(id, job, r -> r.set("parts", parts));
                }
                ObjectNode manifest;
                synchronized (this) { manifest = currentRecord(id, job).deepCopy(); }
                List<Path> zips = new ArrayList<>(); List<Integer> counts = new ArrayList<>();
                int offset = 0; long zipBytes = 0;
                for (int i = 0; i < manifest.path("parts").size(); i++) {
                    JsonNode part = manifest.path("parts").get(i); int count = part.path("pageCount").asInt();
                    if (part.path("index").asInt(-1) != i || count < 1 || count > 200 || part.path("startPage").asInt() != offset + 1
                        || part.path("endPage").asInt() != offset + count) { throw new IllegalArgumentException("拆分页码清单不连续，未入库"); }
                    String name = String.format(Locale.ROOT, "part-%03d", i + 1);
                    if (!part.path("filename").asText().equals(name + ".pdf")) { throw new IllegalArgumentException("拆分文件清单无效"); }
                    Path zip = folder.resolve(name + ".zip");
                    processSource(id, job, i, folder.resolve(name + ".pdf"), zip, count, offset, expected, MineruClient.ZIP_LIMIT - zipBytes);
                    zipBytes += Files.size(zip);
                    if (zipBytes > MineruClient.ZIP_LIMIT) { throw new IllegalArgumentException("拆分结果ZIP累计超过200MiB，未截断入库"); }
                    zips.add(zip); counts.add(count); offset += count;
                }
                if (offset != expected) { throw new IllegalArgumentException("拆分总页数与原件不一致，未入库"); }
                stage(id, job, "validating", "合并全部分卷，恢复原PDF物理页码并核对正文");
                parsed = MineruResult.readParts(zips, counts);
                synchronized (this) { provenance.set("parts", currentRecord(id, job).path("parts").deepCopy()); }
                provenance.put("splitSourceId", initial.path("splitSourceId").asText());
            } else {
                Path zip = folder.resolve("result.zip");
                processSource(id, job, -1, original, zip, expected, 0, expected, MineruClient.ZIP_LIMIT);
                stage(id, job, "validating", "核对全部物理页码、正文和异常页");
                parsed = MineruResult.read(zip, expected);
                synchronized (this) { provenance.put("batchId", currentRecord(id, job).path("batchId").asText()); }
            }
            job.check();
            Path parsedFile = folder.resolve("parsed.json"); writeJson(parsedFile, parsed);
            provenance.set("parameters", initial.path("parameters")); provenance.set("report", parsed.path("report"));
            updateCurrent(id, job, r -> r.set("report", parsed.path("report")));
            stage(id, job, "saving", "页码核验通过，完整保存资料版本");
            return job.commit(() -> {
                ObjectNode current;
                synchronized (this) { current = currentRecord(id, job).deepCopy(); }
                ObjectNode result = store.importDocument(current.path("title").asText(), current.path("documentId").asText(), (ArrayNode) parsed.path("pages"), current.path("filename").asText(), Files.readAllBytes(original), provenance, id);
                return finish(id, job, result);
            });
        } catch (Exception error) {
            synchronized (this) {
                if (owns(id, job)) {
                    ObjectNode r = record(id); recoverSavedBatches(r, root.resolve(id)); ObjectNode committed = store.importResult(id);
                    if (committed != null) { finish(id, job, committed); }
                    else {
                        boolean cancelled = r.path("status").asText().equals("cancelled");
                        String message = error instanceof IllegalArgumentException || error instanceof java.util.concurrent.CancellationException ? error.getMessage() : "本地处理或网络中断，已保留原任务，可继续核查";
                        if (message == null) { message = "处理已暂停"; }
                        if (error instanceof MineruClient.Failure failure) { r.set("errorDetails", failure.diagnostics()); }
                        r.put("status", cancelled ? "cancelled" : r.path("remoteFailed").asBoolean() ? "failed" : "paused").put("error", message).put("message", message); save(r);
                    }
                }
            }
            throw error;
        }
    }
    /** A part owns its cloud identifiers; only the parent import can commit a document. */
    private void processSource(String id, Jobs.Job job, int index, Path input, Path zip, int expected, int offset, int total, long remainingZipBytes) throws Exception {
        ObjectNode initial;
        synchronized (this) { initial = source(currentRecord(id, job), index).deepCopy(); }
        String prefix = index < 0 ? "" : "第" + (index + 1) + "份（原第" + (offset + 1) + "–" + (offset + expected) + "页）：";
        if (!Files.isRegularFile(zip)) {
            String batch = initial.path("batchId").asText();
            boolean uploaded = initial.hasNonNull("uploadedAt");
            Path ticketFile = input.resolveSibling(input.getFileName() + ".upload.json");
            if (batch.isBlank()) {
                if (initial.path("submissionAttempted").asBoolean()) { throw new IllegalArgumentException("上次提交结果不确定，未自动重提；请核查 MinerU 控制台"); }
                stage(id, job, "submitting", prefix + "申请上传地址");
                updateSource(id, job, index, r -> r.put("submissionAttempted", true));
                MineruClient.Ticket ticket = client.requestUpload(input.getFileName().toString(), index < 0 ? id : id + "-" + index, job);
                batch = ticket.batchId(); String savedBatch = batch;
                // Signed URLs are credentials: separate private sidecar, never public journal/provenance.
                writeJson(ticketFile, Json.object().put("batchId", batch).put("uploadUrl", ticket.uploadUrl().toString())
                    .put("sha256", fileHash(input)));
                updateSource(id, job, index, r -> r.put("batchId", savedBatch).put("submittedAt", Instant.now().toString()));
                stage(id, job, "uploading", prefix + "上传至 MinerU"); client.upload(ticket.uploadUrl(), input, job);
                updateSource(id, job, index, r -> r.put("uploadedAt", Instant.now().toString()));
                uploaded = true;
            }
            int failures = 0;
            while (true) {
                job.check(); ObjectNode remote;
                try { remote = client.status(batch, job); failures = 0; }
                catch (MineruClient.Failure e) {
                    if (!e.retryable || ++failures >= 3) { throw e; }
                    stage(id, job, "query-retry", prefix + "查询暂时失败（" + failures + "/3），5秒后继续原任务"); Thread.sleep(5000); continue;
                }
                JsonNode progress = remote.path("extract_progress");
                if (progress.has("total_pages") && progress.path("total_pages").asInt() != expected) { throw new IllegalArgumentException("云端报告页数与上传PDF不一致，暂停核查"); }
                String state = remote.path("state").asText();
                if (state.equals("failed")) {
                    String message = safeRemoteError(remote.path("err_msg").asText());
                    updateCurrent(id, job, r -> { r.put("remoteFailed", true); source(r, index).put("remoteFailed", true); });
                    throw new IllegalArgumentException(prefix + "MinerU 解析失败：" + message + "；原文件未改写");
                }
                if (state.equals("done")) {
                    updateSource(id, job, index, r -> r.put("ocrCompletedAt", Instant.now().toString()));
                    stage(id, job, "downloading", prefix + "云端解析完成，下载结果包");
                    client.download(remote.path("full_zip_url").asText(), zip, job, remainingZipBytes); break;
                }
                if (!Set.of("waiting-file", "pending", "running", "converting").contains(state)) { throw new IllegalArgumentException("MinerU 返回未知任务状态，已暂停核查"); }
                if (state.equals("waiting-file") && expected > 200 && index < 0) {
                    updateCurrent(id, job, r -> r.put("pageLimitExceeded", true));
                    throw new IllegalArgumentException("原文件共" + expected + "页，云端仍等待文件，超过单次200页限制；请选择分卷解析后合并");
                }
                if (state.equals("waiting-file") && !uploaded) {
                    if (!Files.isRegularFile(ticketFile)) {
                        throw new IllegalArgumentException(prefix + "云端仍在等待文件，但旧任务未保存上传地址，无法补传；请核查原任务或重新选择导入，未自动新建云任务");
                    }
                    JsonNode ticket = Json.MAPPER.readTree(Files.readAllBytes(ticketFile));
                    if (!batch.equals(ticket.path("batchId").asText()) || !fileHash(input).equals(ticket.path("sha256").asText())) {
                        throw new IllegalArgumentException(prefix + "上传凭据与原任务或文件不一致，已停止补传");
                    }
                    stage(id, job, "uploading", prefix + "云端确认等待文件，向原任务补传原文件");
                    java.net.URI uploadUrl;
                    try { uploadUrl = java.net.URI.create(ticket.path("uploadUrl").asText()); }
                    catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("保存的上传地址无效，已停止补传"); }
                    client.upload(uploadUrl, input, job);
                    updateSource(id, job, index, r -> r.put("uploadedAt", Instant.now().toString()));
                    uploaded = true;
                    continue;
                }
                int done = progress.path("extracted_pages").asInt();
                if (done < 0 || done > expected) { throw new IllegalArgumentException("云端解析进度超出页数，已暂停核查"); }
                updateCurrent(id, job, r -> { r.put("extractedPages", offset + done); if (index >= 0) { source(r, index).put("extractedPages", done); } });
                String message = switch (state) {
                    case "waiting-file" -> "云端等待确认上传；本地继续查询原任务";
                    case "pending" -> "MinerU 排队中";
                    case "running" -> "整书已解析 " + (offset + done) + "/" + total + " 页";
                    default -> "MinerU 正在转换结果";
                };
                stage(id, job, state, prefix + message); Thread.sleep(5000);
            }
        }
        updateCurrent(id, job, r -> {
            r.put("extractedPages", offset + expected);
            if (index >= 0) { source(r, index).put("status", "completed").put("extractedPages", expected); }
        });
    }
    /** Repair the sidecar -> public journal crash window without making any cloud request. */
    private static void recoverSavedBatches(ObjectNode record, Path folder) {
        if (record.has("parts")) {
            for (int i = 0; i < record.path("parts").size(); i++) {
                restoreBatch((ObjectNode) record.path("parts").get(i), folder.resolve(String.format(Locale.ROOT, "part-%03d.pdf", i + 1)));
            }
        } else { restoreBatch(record, folder.resolve("original.pdf")); }
    }
    private static void restoreBatch(ObjectNode source, Path input) {
        if (!source.path("submissionAttempted").asBoolean() || !source.path("batchId").asText().isBlank()) { return; }
        Path ticketFile = input.resolveSibling(input.getFileName() + ".upload.json");
        try {
            if (!Files.isRegularFile(ticketFile) || Files.isSymbolicLink(ticketFile) || Files.isSymbolicLink(input)) { return; }
            JsonNode ticket = Json.MAPPER.readTree(Files.readAllBytes(ticketFile));
            String batch = ticket.path("batchId").asText();
            if (batch.matches("[A-Za-z0-9_-]+") && fileHash(input).equals(ticket.path("sha256").asText()) && !ticket.path("uploadUrl").asText().isBlank()) {
                source.put("batchId", batch);
            }
        } catch (Exception ignored) { /* Unverified credentials cannot authorize resubmission. */ }
    }
    private static String fileHash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static ObjectNode source(ObjectNode record, int index) { return index < 0 ? record : (ObjectNode) record.path("parts").get(index); }
    private void updateSource(String id, Jobs.Job job, int index, java.util.function.Consumer<ObjectNode> change) throws Exception {
        updateCurrent(id, job, r -> change.accept(source(r, index)));
    }
    private synchronized ObjectNode finish(String id, Jobs.Job job, ObjectNode result) throws Exception {
        if (!updateIfCurrent(id, job, r -> { r.put("status", "completed").put("phase", "completed").put("completedAt", Instant.now().toString()).put("message", "完整解析结果已入库"); r.set("result", result); })) {
            throw new java.util.concurrent.CancellationException("导入尝试已被更新任务取代");
        }
        return result;
    }
    private void stage(String id, Jobs.Job job, String phase, String message) throws Exception {
        job.event(phase, message);
        updateCurrent(id, job, r -> {
            if (!phase.equals(r.path("phase").asText())) { r.withArray("timeline").add(Json.object().put("phase", phase).put("at", Instant.now().toString())); }
            r.put("phase", phase).put("message", message);
        });
    }
    private synchronized boolean owns(String id, Jobs.Job job) { return job.id.equals(record(id).path("jobId").asText()); }
    private synchronized ObjectNode currentRecord(String id, Jobs.Job job) {
        job.check();
        if (!owns(id, job)) { throw new java.util.concurrent.CancellationException("导入尝试已被更新任务取代"); }
        return record(id);
    }
    /** Persist a change only while this worker still owns the import journal. */
    synchronized boolean updateIfCurrent(String id, Jobs.Job job, java.util.function.Consumer<ObjectNode> change) throws Exception {
        if (!owns(id, job)) { return false; }
        ObjectNode r = record(id); change.accept(r); save(r); return true;
    }
    private void updateCurrent(String id, Jobs.Job job, java.util.function.Consumer<ObjectNode> change) throws Exception {
        job.check();
        if (!updateIfCurrent(id, job, change)) { throw new java.util.concurrent.CancellationException("导入尝试已被更新任务取代"); }
    }
    private void save(ObjectNode value) throws Exception { value.put("updatedAt", Instant.now().toString()); writeJson(root.resolve(value.path("id").asText()).resolve("state.json"), value); }
    private static void writeJson(Path target, ObjectNode value) throws Exception {
        Path temporary = Files.createTempFile(target.getParent(), "journal-", ".tmp");
        try { privateFile(temporary); Files.write(temporary, Json.MAPPER.writeValueAsBytes(value)); Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static void privateFile(Path path) throws Exception { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
    private static void privateDirectory(Path path) throws Exception { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); }
    private static String safeRemoteError(String raw) {
        String safe = raw.replaceAll("(?i)https?://\\S+", "[文件链接]").replaceAll("(?i)bearer\\s+\\S+", "[凭据]");
        try { String token = MineruClient.configuredToken(); safe = safe.replace(token, "[凭据]"); } catch (Exception ignored) { }
        return safe.isBlank() ? "供应商未给出原因" : safe;
    }
}
