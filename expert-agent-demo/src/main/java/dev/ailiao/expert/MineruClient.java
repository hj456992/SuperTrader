package dev.ailiao.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** MinerU official cloud API. Credentials are confined to API requests, never signed blob URLs. */
final class MineruClient {
    static final long ZIP_LIMIT = 200L * 1024 * 1024;
    static final long JSON_LIMIT = 100L * 1024 * 1024;
    interface TokenSource { String read() throws IOException; }
    record Ticket(String batchId, URI uploadUrl) {}
    static final class Failure extends IllegalArgumentException {
        final boolean retryable;
        final String code, phase;
        final List<String> causeTypes;
        final Integer httpStatus;
        Failure(String message, boolean retryable) { this(message, retryable, "MINERU_FAILURE", "", List.of(), null); }
        private Failure(String message, boolean retryable, String code, String phase, List<String> causeTypes, Integer httpStatus) {
            super(message, null); this.retryable = retryable; this.code = code; this.phase = phase;
            this.causeTypes = List.copyOf(causeTypes); this.httpStatus = httpStatus;
        }
        ObjectNode diagnostics() {
            ObjectNode result = Json.object().put("code", code).put("phase", phase).put("retryable", retryable);
            result.set("causeTypes", Json.MAPPER.valueToTree(causeTypes));
            if (httpStatus != null) { result.put("httpStatus", httpStatus); }
            return result;
        }
    }
    private final URI base;
    private final TokenSource token;
    private final HttpClient api = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final HttpClient blobs = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    MineruClient() { this(URI.create("https://mineru.net"), MineruClient::configuredToken); }
    MineruClient(URI base, TokenSource token) { this.base = base; this.token = token; }
    static String configuredToken() throws IOException {
        String value = System.getenv("MINERU_API_TOKEN");
        if (value == null || value.isBlank()) {
            String configured = System.getenv("MINERU_API_TOKEN_FILE");
            Path file = configured == null || configured.isBlank() ? Path.of(System.getProperty("user.home"), ".config/mineru/token") : Path.of(configured);
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) { throw new Failure("请在本机私密文件配置 MinerU Token", false); }
            value = Files.readString(file);
        }
        value = value.strip();
        if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) { throw new Failure("MinerU Token 配置无效，请保存纯 Token", false); }
        return value;
    }
    void checkConfigured() throws IOException { token.read(); }
    static ObjectNode parameters(String name, String dataId) {
        ObjectNode request = Json.object().put("model_version", "vlm").put("language", "ch").put("enable_table", true).put("enable_formula", true);
        request.set("files", Json.array().add(Json.object().put("name", name).put("data_id", dataId).put("is_ocr", true)));
        return request;
    }
    Ticket requestUpload(String name, String dataId, Jobs.Job job) throws Exception {
        ObjectNode data = api("/api/v4/file-urls/batch", parameters(name, dataId), job);
        String batch = data.path("batch_id").asText(); JsonNode urls = data.path("file_urls");
        if (!batch.matches("[A-Za-z0-9_-]+") || !urls.isArray() || urls.size() != 1) { throw new Failure("MinerU 上传申请返回格式异常，未自动重提", false); }
        return new Ticket(batch, blobUri(urls.get(0).asText()));
    }
    ObjectNode status(String batchId, Jobs.Job job) throws Exception {
        if (!batchId.matches("[A-Za-z0-9_-]+")) { throw new Failure("MinerU 任务编号无效", false); }
        ObjectNode data = api("/api/v4/extract-results/batch/" + batchId, null, job);
        JsonNode results = data.path("extract_result");
        if (!results.isArray() || results.size() != 1 || !(results.get(0) instanceof ObjectNode value)) { throw new Failure("MinerU 任务结果数量或格式异常", false); }
        return value;
    }
    private ObjectNode api(String path, ObjectNode body, Jobs.Job job) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(job.remaining(30)).header("Authorization", "Bearer " + token.read()).header("Accept", "application/json");
        if (body != null) { request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())); }
        HttpResponse<byte[]> response = send(api, request.build(), info -> bounded(HttpResponse.BodySubscribers.ofByteArray(), JSON_LIMIT), job, 30, "API 请求");
        httpStatus(response.statusCode(), "API 请求");
        JsonNode parsed;
        try { parsed = Json.MAPPER.readTree(response.body()); } catch (IOException e) { throw new Failure("MinerU 返回了无效 JSON", false); }
        String code = parsed.path("code").asText("missing");
        if (!code.equals("0")) { throw new Failure(errorMessage(code), Set.of("-10001", "-60007", "-60009").contains(code)); }
        if (!(parsed.path("data") instanceof ObjectNode value)) { throw new Failure("MinerU API 缺少任务数据", false); }
        return value;
    }
    void upload(URI url, Path original, Jobs.Job job) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(blobUri(url.toString())).timeout(job.remaining(300)).PUT(HttpRequest.BodyPublishers.ofFile(original)).build();
        HttpResponse<Void> response = send(blobs, request, HttpResponse.BodyHandlers.discarding(), job, 300, "文件上传");
        httpStatus(response.statusCode(), "文件上传");
    }
    void download(String url, Path target, Jobs.Job job) throws Exception {
        download(url, target, job, ZIP_LIMIT);
    }
    void download(String url, Path target, Jobs.Job job, long remainingBytes) throws Exception {
        if (remainingBytes <= 0 || remainingBytes > ZIP_LIMIT) { throw new Failure("结果ZIP累计超过200MiB，未继续下载", false); }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(blobUri(url)).timeout(job.remaining(900)).GET().build();
            HttpResponse<Path> response = send(blobs, request, info -> bounded(HttpResponse.BodySubscribers.ofFile(part), remainingBytes), job, 900, "结果下载");
            httpStatus(response.statusCode(), "结果下载"); job.check();
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } finally { Files.deleteIfExists(part); }
    }
    private URI blobUri(String raw) {
        try {
            URI uri = URI.create(raw);
            boolean fixture = "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == base.getPort();
            if ((!"https".equals(uri.getScheme()) && !fixture) || uri.getHost() == null || uri.getUserInfo() != null) { throw new IllegalArgumentException(); }
            return uri;
        } catch (IllegalArgumentException e) { throw new Failure("MinerU 返回的文件地址无效", false); }
    }
    private static void httpStatus(int status, String phase) {
        if (status >= 200 && status < 300) { return; }
        String explanation = status == 401 || status == 403 ? "鉴权或文件链接权限失败" : status == 429 ? "请求频率或额度受限" : "服务响应异常";
        throw new Failure("MinerU " + phase + "：" + explanation + "（HTTP " + status + "）", status == 429 || status >= 500,
            "MINERU_HTTP_ERROR", phase, List.of(), status);
    }
    static String errorMessage(String code) {
        String safeCode = code.matches("[A-Za-z0-9_-]+") ? code : "unknown";
        String reason = switch (safeCode) {
            case "A0202" -> "Token 无效"; case "A0211" -> "Token 已过期";
            case "-60003" -> "原文件读取失败（可能与文件结构或加密有关），未改写原件";
            case "-60006" -> "超过云端页数限制"; case "-60018", "-60019" -> "解析额度不足";
            case "-60007" -> "模型服务暂不可用"; case "-60009" -> "云端队列已满";
            default -> "请求未完成，请核对官方错误码";
        };
        return "MinerU：" + reason + "（" + safeCode + "）";
    }
    private static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, Jobs.Job job, int seconds, String phase) throws Exception {
        job.check(); CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, handler);
        try { return future.get(job.remaining(seconds).toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
        catch (ExecutionException | TimeoutException e) {
            throw transportFailure(e, phase);
        } finally { if (!future.isDone()) { future.cancel(true); } }
    }
    /** Copy only bounded type names; retaining the original cause can expose signed URLs in stack traces. */
    private static Failure transportFailure(Throwable error, String phase) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> types = new ArrayList<>(); String code = "MINERU_TRANSPORT_FAILURE"; int priority = 0;
        Failure known = null;
        for (Throwable cause = error; cause != null && seen.size() < 12 && seen.add(cause); cause = cause.getCause()) {
            types.add(cause.getClass().getName());
            if (cause instanceof Failure failure) { known = failure; break; }
            int rank = cause instanceof javax.net.ssl.SSLException ? 5 : cause instanceof HttpConnectTimeoutException ? 4
                : cause instanceof HttpTimeoutException || cause instanceof TimeoutException || cause instanceof SocketTimeoutException ? 3
                : cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException ? 2
                : cause instanceof IOException ? 1 : 0;
            if (rank > priority) {
                priority = rank;
                code = switch (rank) {
                    case 5 -> "MINERU_TLS_FAILURE"; case 4 -> "MINERU_CONNECT_TIMEOUT"; case 3 -> "MINERU_REQUEST_TIMEOUT";
                    case 2 -> "MINERU_CONNECTION_FAILED"; case 1 -> "MINERU_NETWORK_IO"; default -> "MINERU_TRANSPORT_FAILURE";
                };
            }
        }
        if (known != null) { return new Failure(known.getMessage(), known.retryable, known.code, phase, types, known.httpStatus); }
        return new Failure("MinerU " + phase + "网络失败或超时（" + code + "）；未自动重新提交解析", true, code, phase, types, null);
    }
    private static <T> HttpResponse.BodySubscriber<T> bounded(HttpResponse.BodySubscriber<T> delegate, long limit) {
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription; private long bytes; private boolean stopped;
            public CompletionStage<T> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> items) {
                if (stopped) { return; }
                for (ByteBuffer item : items) { bytes += item.remaining(); }
                if (bytes > limit) { stopped = true; subscription.cancel(); delegate.onError(new Failure("MinerU 结果超过已批准的下载体积上限，未截断入库", false)); }
                else { delegate.onNext(items); }
            }
            public void onError(Throwable error) { if (!stopped) { stopped = true; delegate.onError(error); } }
            public void onComplete() { if (!stopped) { stopped = true; delegate.onComplete(); } }
        };
    }
}
