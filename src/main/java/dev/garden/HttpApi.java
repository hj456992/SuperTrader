package dev.garden;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** 只监听 loopback；严格 Host/Origin、会话 Cookie 和 JSON 写请求隔离本地资料。 */
final class HttpApi implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(6);
    private final String session;
    private final String host;
    private final Path web;
    private final GardenService service;
    private final RanchService ranch;

    /** @param port 独立本地端口。 @param web 前端构建目录。 @param service 业务服务。 */
    HttpApi(int port, Path web, GardenService service, RanchService ranch) throws Exception {
        this.web = web.toRealPath();
        this.service = service;
        this.ranch = ranch;
        this.host = "127.0.0.1:" + port;
        var token = new byte[32];
        new SecureRandom().nextBytes(token);
        session = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /** 资源登记成功后才开启监听。 */
    void start() {
        server.start();
    }

    /** @param exchange HTTP 请求；错误响应不回显内部异常。 */
    private void handle(HttpExchange exchange) {
        try {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            if (!host.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                json(exchange, 403, Map.of("error", "仅限本地访问。"));
                return;
            }
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            if (path.startsWith("/api/")) {
                var cookies = Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"), "");
                if (!Arrays.asList(cookies.split(";\\s*")).contains("garden_session=" + session)) {
                    json(exchange, 401, Map.of("error", "请先打开 Demo 首页。"));
                    return;
                }
                if ("GET".equals(method) && "/api/state".equals(path)) {
                    json(exchange, 200, service.state());
                    return;
                }
                if ("GET".equals(method) && "/api/ranch-state".equals(path)) {
                    json(exchange, 200, ranch.state()); return;
                }
                if ("GET".equals(method) && "/api/ranch-sources".equals(path)) {
                    json(exchange, 200, ranch.sources()); return;
                }
                if (!"POST".equals(method)
                    || !("http://" + host).equals(exchange.getRequestHeaders().getFirst("Origin"))
                    || !"1".equals(exchange.getRequestHeaders().getFirst("X-Garden-Request"))
                    || !Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "").startsWith("application/json")) {
                    json(exchange, 403, Map.of("error", "请求来源无效。"));
                    return;
                }
                int limit = "/api/ranch-knowledge-upload".equals(path) ? 14100000 : 100000;
                var bytes = exchange.getRequestBody().readNBytes(limit + 1);
                if (bytes.length > limit) {
                    json(exchange, 413, Map.of("error", "导入内容太大。"));
                    return;
                }
                var body = Store.JSON.readTree(bytes);
                if (body == null || !body.isObject()) {
                    throw new IllegalArgumentException("请输入有效 JSON。");
                }
                json(exchange, 200, path.startsWith("/api/ranch-") ? ranch.command(path.substring(11), body) : service.command(path.substring(5), body));
                return;
            }
            if (!"GET".equals(method)) {
                json(exchange, 405, Map.of("error", "不支持的请求。"));
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path) || "/conversations.html".equals(path)) {
                headers.set("Set-Cookie", "garden_session=" + session + "; HttpOnly; SameSite=Strict; Path=/");
                if ("/".equals(path)) path = "/index.html";
            }
            var file = web.resolve(path.substring(1)).normalize();
            if (!file.startsWith(web) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(web)) {
                json(exchange, 404, Map.of("error", "页面不存在。"));
                return;
            }
            var type = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" : path.endsWith(".json") ? "application/json" : "text/html";
            headers.set("Content-Type", type + "; charset=utf-8");
            var bytes = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IllegalArgumentException error) {
            safeError(exchange, 400, error.getMessage());
        } catch (IllegalStateException error) {
            safeError(exchange, 409, error.getMessage());
        } catch (Exception error) {
            safeError(exchange, 500, "操作未完成，请检查本地服务后重试。");
            System.err.println("Garden request failed: " + error.getClass().getSimpleName());
        } finally {
            exchange.close();
        }
    }

    /** @param exchange 当前请求。 @param status HTTP 状态。 @param value 可序列化结果。 */
    private void json(HttpExchange exchange, int status, Object value) throws Exception {
        var bytes = Store.JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** @param exchange 当前请求。 @param status HTTP 状态。 @param message 不含秘密的用户提示。 */
    private void safeError(HttpExchange exchange, int status, String message) {
        try {
            json(exchange, status, Map.of("error", message));
        } catch (Exception ignored) {
            // 客户端断连时不再重试写入。
        }
    }

    /** 停止监听并释放请求线程。 */
    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
