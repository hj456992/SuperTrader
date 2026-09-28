package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 受限子进程调用本地 pypdf，失败时不写资料库。 */
final class PdfImport {
    private final String python;
    private final Path script;
    private final Path temporary;
    /** 保存本地解析配置。@param python 解释器 @param script 解析脚本 @param temporary 私有工作目录 */
    PdfImport(String python, Path script, Path temporary) { this.python = python; this.script = script; this.temporary = temporary; }
    /** Create private, page-count-checked 200-page PDF parts without changing the source. */
    ArrayNode split(Path original, Path folder, Jobs.Job job) throws Exception {
        job.check();
        if (Files.size(original) > 20L * 1024 * 1024) throw new IllegalArgumentException("PDF 不得超过 20MB");
        int expected = inspect(original, job).path("pageCount").asInt();
        Files.createDirectories(temporary);
        Path output = Files.createTempFile(temporary, "split-", ".json");
        Process process = null;
        try {
            process = new ProcessBuilder(python, script.toString(), original.toString(), output.toString(),
                    folder.toString(), "--split")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            while (!process.waitFor(1, TimeUnit.SECONDS)) job.check();
            job.check();
            if (process.exitValue() != 0 || Files.size(output) > 64 * 1024)
                throw new IllegalArgumentException("PDF 拆分失败，请检查本机 pypdf");
            ObjectNode result = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(output));
            if (result.has("error")) throw new IllegalArgumentException(result.path("error").asText());
            if (!(result.path("parts") instanceof ArrayNode parts) || parts.size() != (expected + 199) / 200)
                throw new IllegalArgumentException("PDF 分片清单无效");
            for (int i = 0; i < parts.size(); i++) {
                int start = i * 200 + 1, end = Math.min(expected, start + 199);
                String filename = "part-%03d.pdf".formatted(i + 1);
                var part = parts.get(i);
                if (part.path("index").asInt(-1) != i || part.path("startPage").asInt(-1) != start
                        || part.path("endPage").asInt(-1) != end || part.path("pageCount").asInt(-1) != end - start + 1
                        || !filename.equals(part.path("filename").asText())
                        || !Files.isRegularFile(folder.resolve(filename)))
                    throw new IllegalArgumentException("PDF 分片清单无效");
            }
            return parts;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
    /** Inspect original page count only; an empty-password PDF remains byte-for-byte unchanged. */
    ObjectNode inspect(Path original, Jobs.Job job) throws Exception {
        Files.createDirectories(temporary);
        Path output = Files.createTempFile(temporary, "inspect-", ".json");
        Process process = null;
        try {
            process = new ProcessBuilder(python, script.toString(), original.toString(), output.toString(), "--inspect")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            while (!process.waitFor(1, TimeUnit.SECONDS)) { job.check(); }
            job.check();
            if (process.exitValue() != 0) { throw new IllegalArgumentException("无法读取 PDF 页数，请检查本机 pypdf"); }
            ObjectNode result = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(output));
            if (result.has("error")) { throw new IllegalArgumentException(result.path("error").asText()); }
            int count = result.path("pageCount").asInt();
            if (count < 1 || count > 600) { throw new IllegalArgumentException("PDF 页数无效或超过 MinerU 600 页上限"); }
            return result;
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            Files.deleteIfExists(output);
        }
    }
    /** 抽取页文本，超时强制停止子进程。@param bytes 上传字节 */
    ArrayNode parse(byte[] bytes) throws Exception {
        if (bytes.length > 20 * 1024 * 1024 || bytes.length < 5 || !new String(bytes, 0, Math.min(1024, bytes.length), StandardCharsets.ISO_8859_1).stripLeading().startsWith("%PDF-")) { throw new IllegalArgumentException("请上传不超过 20MB 的有效 PDF"); }
        Files.createDirectories(temporary);
        Path input = Files.createTempFile(temporary, "pdf-", ".pdf"), output = Files.createTempFile(temporary, "parsed-", ".json");
        Process process = null;
        try {
            Files.write(input, bytes);
            process = new ProcessBuilder(python, script.toString(), input.toString(), output.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) { throw new IllegalArgumentException("PDF 解析超过45秒，请拆分文档后重试"); }
            if (process.exitValue() != 0 || Files.size(output) > 4 * 1024 * 1024) { throw new IllegalArgumentException("PDF 解析失败，请检查本地 pypdf 依赖"); }
            ObjectNode result = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(output));
            if (result.has("error")) { throw new IllegalArgumentException(result.path("error").asText()); }
            if (!(result.path("pages") instanceof ArrayNode pages)) { throw new IllegalArgumentException("PDF 解析结果无效"); }
            return pages;
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); }
            Files.deleteIfExists(input); Files.deleteIfExists(output);
        }
    }
}
