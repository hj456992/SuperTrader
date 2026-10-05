package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 仅监听本机的 Demo 管理界面；业务编排可脱离此页面复用。 */
final class LabHttp implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final LabStore store;
    private final MineruImport imports;
    private final ExpertBuilder builder;
    private final ExpertRuntime runtime;
    private final Jobs jobs;
    private final ProductionHttp production;
    private final String csrf = Json.id();
    private final int port;
    /** 连接应用服务与独立本机端口。@param port 端口 @param store 仓库 @param pdf 解析器 @param builder 生成服务 @param runtime 试聊服务 @param jobs 后台任务 */
    LabHttp(int port, LabStore store, MineruImport imports, ExpertBuilder builder, ExpertRuntime runtime, Jobs jobs) throws Exception {
        this(port,store,imports,builder,runtime,jobs,null);
    }
    LabHttp(int port, LabStore store, MineruImport imports, ExpertBuilder builder, ExpertRuntime runtime, Jobs jobs, ProductionService productionService) throws Exception {
        this.port = port; this.store = store; this.imports = imports; this.builder = builder; this.runtime = runtime; this.jobs = jobs;
        this.production=new ProductionHttp(productionService);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 20); server.setExecutor(executor); server.createContext("/", this::handle);
    }
    /** 开始监听。 */
    void start() { server.start(); }
    /** 关闭端口和请求线程。 */
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
    /** 分发已校验的本机请求。@param exchange 请求与响应 */
    private void handle(HttpExchange exchange) {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!Set.of("127.0.0.1:" + port, "localhost:" + port).contains(host == null ? "" : host)) { send(exchange, 403, Json.object().put("error", "仅允许本机访问")); return; }
            String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
            if(path.startsWith("/api/expert-production/v1/")){
                if(!method.equals("GET")){
                    String origin=exchange.getRequestHeaders().getFirst("Origin");
                    if(!csrf.equals(exchange.getRequestHeaders().getFirst("X-Lab-Token"))||(origin!=null&&!Set.of("http://127.0.0.1:"+port,"http://localhost:"+port).contains(origin))){send(exchange,403,Json.object().put("error","页面已过期，请刷新后重试"));return;}
                }
                if(production.handle(exchange))return;
            }
            if (method.equals("GET")) {
                if (path.equals("/api/state")) { ObjectNode value = store.state(); value.put("csrf", csrf); value.set("imports", imports.list()); value.set("jobs", jobs.snapshots()); send(exchange, 200, value); return; }
                if (path.startsWith("/api/imports/")) { send(exchange, 200, imports.get(path.substring(13))); return; }
                if (path.equals("/api/document-version")) { send(exchange, 200, store.documentVersion(query(exchange).getOrDefault("versionId", ""))); return; }
                if (path.equals("/api/original")) {
                    java.nio.file.Path original = store.original(query(exchange).getOrDefault("versionId", ""));
                    exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=original.pdf");
                    bytes(exchange, 200, "application/pdf", java.nio.file.Files.readAllBytes(original)); return;
                }
                if (path.startsWith("/api/jobs/")) { send(exchange, 200, jobs.get(path.substring(10)).snapshot()); return; }
                if (path.equals("/api/passage")) { send(exchange, 200, store.passage(query(exchange).getOrDefault("id", ""))); return; }
                if (path.equals("/api/health")) { send(exchange, 200, Json.object().put("status", "ready").put("runtime", "dsh-java / AgentScope")); return; }
                Map<String, String> resources = Map.of("/", "index.html", "/app.js", "app.js", "/style.css", "style.css", "/production.js", "production.js", "/production.css", "production.css", "/production-api.js", "production-api.js");
                if (resources.containsKey(path)) {
                    try (InputStream stream = getClass().getResourceAsStream("/web/" + resources.get(path))) {
                        if (stream == null) { send(exchange, 404, Json.object().put("error", "页面尚未构建")); return; }
                        String type = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" : "text/html";
                        bytes(exchange, 200, type + "; charset=utf-8", stream.readAllBytes()); return;
                    }
                }
            }
            if (method.equals("POST")) {
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (!csrf.equals(exchange.getRequestHeaders().getFirst("X-Lab-Token")) || (origin != null && !Set.of("http://127.0.0.1:" + port, "http://localhost:" + port).contains(origin))) { send(exchange, 403, Json.object().put("error", "页面已过期，请刷新后重试")); return; }
                if (path.equals("/api/documents")) {
                    byte[] raw = body(exchange, 20 * 1024 * 1024); Map<String, String> values = query(exchange);
                    send(exchange, 202, imports.submit(values.getOrDefault("title", "未命名资料"), values.getOrDefault("documentId", ""), values.getOrDefault("filename", "document.pdf"), raw)); return;
                }
                ObjectNode request = Json.parse(new String(body(exchange, 30000), StandardCharsets.UTF_8));
                if (path.startsWith("/api/imports/") && path.endsWith("/split")) {
                    send(exchange, 202, imports.split(path.substring(13, path.length() - 6))); return;
                }
                if (path.startsWith("/api/imports/") && path.endsWith("/resume")) {
                    send(exchange, 202, imports.resume(path.substring(13, path.length() - 7))); return;
                }
                if (path.equals("/api/experts")) {
                    Json.required(request, "name", 80); Json.required(request, "duty", 2000); store.selectedChunks(request.path("selections"));
                    send(exchange, 202, Json.object().put("jobId", jobs.start("generate", "", job -> builder.generate(request, job)).id)); return;
                }
                if (path.equals("/api/chat")) {
                    Json.required(request, "message", 6000);
                    store.expertVersion(Json.required(request, "expertId", 80), Json.required(request, "versionId", 80));
                    String key = request.path("conversationId").asText("");
                    send(exchange, 202, Json.object().put("jobId", jobs.start("chat", key.isBlank() ? "" : "chat:" + key, job -> runtime.chat(request, job)).id)); return;
                }
                if (path.equals("/api/activate")) {
                    store.activate(Json.required(request, "expertId", 80), Json.required(request, "versionId", 80)); send(exchange, 200, Json.object().put("ok", true)); return;
                }
                if (path.startsWith("/api/jobs/") && path.endsWith("/cancel")) {
                    String jobId = path.substring(10, path.length() - 7); if (!imports.cancelJob(jobId)) { jobs.get(jobId).cancel(); } send(exchange, 200, Json.object().put("ok", true)); return;
                }
            }
            send(exchange, 404, Json.object().put("error", "接口不存在"));
        } catch (IllegalArgumentException error) {
            try { send(exchange, 400, Json.object().put("error", error.getMessage() == null ? "请求无效" : error.getMessage())); } catch (Exception ignored) { }
        } catch (Exception error) {
            try { send(exchange, 500, Json.object().put("error", "操作未完成，请检查文件格式、服务状态或稍后重试")); } catch (Exception ignored) { }
        } finally { exchange.close(); }
    }
    /** 限制请求体，避免未声明长度的上传绕过限额。@param exchange 请求 @param max 上限 */
    private static byte[] body(HttpExchange exchange, int max) throws IOException {
        byte[] value = exchange.getRequestBody().readNBytes(max + 1);
        if (value.length > max) { throw new IllegalArgumentException("请求内容超过限额"); }
        return value;
    }
    /** 解码查询参数。@param exchange 请求 */
    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>(); String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) { for (String item : raw.split("&")) { String[] pair = item.split("=", 2); if (pair.length == 2) { result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); } } }
        return result;
    }
    /** 发送 JSON。@param exchange 响应 @param code 状态 @param value 内容 */
    private static void send(HttpExchange exchange, int code, ObjectNode value) throws IOException { bytes(exchange, code, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(value)); }
    /** 统一浏览器安全响应头。@param exchange 响应 @param code 状态 @param type 类型 @param data 内容 */
    private static void bytes(HttpExchange exchange, int code, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type); exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'");
        exchange.sendResponseHeaders(code, data.length); exchange.getResponseBody().write(data);
    }
}
