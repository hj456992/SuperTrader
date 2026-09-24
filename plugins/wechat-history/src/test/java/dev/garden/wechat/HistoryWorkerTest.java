package dev.garden.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    HttpServer server;
    List<String> calls = new CopyOnWriteArrayList<>();
    List<JsonNode> batches = new CopyOnWriteArrayList<>();
    List<JsonNode> statuses = new CopyOnWriteArrayList<>();
    volatile int batchStatus = 200;
    volatile JsonNode checkpoint;
    Map<String, Object> config;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            String route = exchange.getRequestURI().getPath();
            calls.add(route);
            if (!"private-test-token".equals(exchange.getRequestHeaders().getFirst("X-Logbook-Token"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            int status = 200;
            byte[] body = "{}".getBytes();
            if (route.endsWith("checkpoint")) {
                var response = JSON.createObjectNode();
                response.set("checkpoint", checkpoint);
                body = JSON.writeValueAsBytes(response);
            } else {
                JsonNode payload = JSON.readTree(exchange.getRequestBody());
                if (route.endsWith("batch")) {
                    batches.add(payload);
                    status = batchStatus;
                    if (status == 200) checkpoint = payload.get("checkpoint");
                } else statuses.add(payload);
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Files.writeString(directory.resolve("token"), "private-test-token");
        Files.writeString(directory.resolve("keys"), "fixture-key-present");
        Files.writeString(directory.resolve("cli.json"), valid());
        config = new HashMap<>(Map.of(
            "commandPrefix", List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), FixtureCli.class.getName()),
            "cliConfig", directory.resolve("cli.json").toString(),
            "keyFile", directory.resolve("keys").toString(),
            "dataDir", directory.resolve("private").toString(),
            "logbookUrl", "http://127.0.0.1:" + server.getAddress().getPort(),
            "tokenFile", directory.resolve("token").toString(),
            "timeoutSeconds", 5));
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void cliProducesStructuredOutput() throws Exception {
        Path cursor = directory.resolve("cursor-test");
        Files.writeString(cursor, "null");
        try (var process = new CliProcess(HistoryConfig.from(config))) {
            assertEquals("aichat.history.v1", JSON.readTree(process.execute(cursor)).path("schema").asText());
        }
    }

    @Test void missingKeyNeverStartsCliAndReportsSetupRequired() throws Exception {
        Files.delete(directory.resolve("keys"));
        try (var worker = new HistoryWorker(config)) {
            worker.tick();
            worker.tick();
        }
        assertFalse(Files.exists(directory.resolve("invoked")));
        assertEquals(2, statuses.size());
        assertEquals("setup_required", statuses.get(0).path("status").asText());
        assertTrue(batches.isEmpty());
    }

    @Test void successfulBatchUsesExactCommandAndCommitsBeforeNextCheckpoint() throws Exception {
        try (var worker = new HistoryWorker(config)) {
            worker.tick();
            worker.tick();
        }
        assertEquals(2, batches.size());
        assertEquals("connected", statuses.get(1).path("status").asText());
        assertEquals("aichat.history.v1", batches.get(0).path("schema").asText());
        assertEquals("account-one", JSON.readTree(Files.readString(directory.resolve("seen-cursor"))).path("account").asText());
        assertEquals(List.of("/v1/history-checkpoint", "/v1/history-batch", "/v1/history-status", "/v1/history-checkpoint", "/v1/history-batch", "/v1/history-status"), calls);
    }

    @Test void rejectedBatchRetriesSamePayloadWithoutFetchingNewCheckpoint() throws Exception {
        batchStatus = 503;
        try (var worker = new HistoryWorker(config)) {
            worker.tick();
            batchStatus = 200;
            worker.tick();
        }
        assertEquals(1, calls.stream().filter(s -> s.endsWith("checkpoint")).count());
        assertEquals(2, batches.size());
        assertEquals(batches.get(0), batches.get(1));
        assertEquals(2, statuses.size());
        assertEquals("error", statuses.get(0).path("status").asText());
    }

    @Test void committedPageWithMoreHistoryRemainsSyncing() throws Exception {
        Files.writeString(directory.resolve("cli.json"), valid().replace("\"has_more\":false", "\"has_more\":true"));
        try (var worker = new HistoryWorker(config)) { worker.tick(); }
        assertEquals(1, batches.size());
        assertEquals(1, statuses.size());
        assertEquals("syncing", statuses.get(0).path("status").asText());
    }

    @Test void wrongGroupNeverPostsOrReportsConnected() throws Exception {
        Files.writeString(directory.resolve("cli.json"), valid().replace("卧底不追高", "其他群"));
        try (var worker = new HistoryWorker(config)) { worker.tick(); }
        assertTrue(batches.isEmpty());
        assertEquals(1, statuses.size());
        assertEquals("error", statuses.get(0).path("status").asText());
    }

    @Test void changedAccountNeverPosts() throws Exception {
        checkpoint = JSON.readTree("{\"account\":\"other-account\",\"chat\":\"room@chatroom\",\"cursor\":{},\"salts\":{}}");
        try (var worker = new HistoryWorker(config)) { worker.tick(); }
        assertTrue(batches.isEmpty());
        assertEquals(1, statuses.size());
        assertEquals("error", statuses.get(0).path("status").asText());
    }

    @Test void failedProcessDoesNotExposeStderrOrPostBatch() throws Exception {
        Files.writeString(directory.resolve("cli.json"), "FAIL");
        try (var worker = new HistoryWorker(config)) { worker.tick(); }
        assertTrue(batches.isEmpty());
        assertEquals(1, statuses.size());
        assertEquals("error", statuses.get(0).path("status").asText());
        assertFalse(statuses.toString().contains("SECRET"));
    }

    @Test void timeoutKillsChildAndReportsError() throws Exception {
        config.put("timeoutSeconds", 1);
        Files.writeString(directory.resolve("cli.json"), "SLEEP");
        long started = System.nanoTime();
        try (var worker = new HistoryWorker(config)) { worker.tick(); }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 6);
        assertTrue(Files.exists(directory.resolve("invoked")));
        long pid = Long.parseLong(Files.readString(directory.resolve("invoked")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals(1, statuses.size());
        assertEquals("error", statuses.get(0).path("status").asText());
    }

    @Test void oversizedOutputIsRejected() throws Exception {
        Files.writeString(directory.resolve("cli.json"), "LARGE");
        try (var worker = new HistoryWorker(config)) { worker.tick(); }
        assertTrue(batches.isEmpty());
        assertEquals(1, statuses.size());
        assertEquals("error", statuses.get(0).path("status").asText());
    }

    @Test void closeKillsRunningProcessAndReleasesDirectoryLock() throws Exception {
        config.put("timeoutSeconds", 30);
        Files.writeString(directory.resolve("cli.json"), "SLEEP");
        var worker = new HistoryWorker(config);
        var thread = new Thread(worker::tick);
        thread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(directory.resolve("invoked")) && System.nanoTime() < deadline) Thread.sleep(10);
        worker.close();
        thread.join(3000);
        assertFalse(thread.isAlive());
        assertTrue(Files.exists(directory.resolve("invoked")));
        long pid = Long.parseLong(Files.readString(directory.resolve("invoked")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        try (var replacement = new HistoryWorker(config)) { }
    }

    @Test void duplicateDirectoryOwnerIsRejected() throws Exception {
        try (var worker = new HistoryWorker(config)) {
            assertThrows(Exception.class, () -> new HistoryWorker(config));
        }
    }

    @Test void standaloneHostStaysAliveUntilWorkerIsClosed() throws Exception {
        Files.delete(directory.resolve("keys"));
        Path configuration = directory.resolve("worker-config.json");
        Files.writeString(configuration, JSON.writeValueAsString(config));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                FixtureHost.class.getName(), configuration.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(directory.resolve("host-started")) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("host-started")));
            assertFalse(process.waitFor(100, TimeUnit.MILLISECONDS), "host exited before dispose");
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "host stayed alive after dispose");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test void nonLoopbackConfigurationIsRejected() {
        config.put("logbookUrl", "http://example.com");
        assertThrows(IllegalArgumentException.class, () -> new HistoryWorker(config));
    }

    static String valid() {
        return """
            {"schema":"aichat.history.v1","target":"卧底不追高","account_fingerprint":"account-one","chat_id":"room@chatroom",
             "rows":[{"database":"message_0.db","local_id":1,"server_id":"8","sender_id":"sender-one","author":"Fixture","text":"Synthetic fixture","create_time":100,"local_type":1}],
             "checkpoint":{"account":"account-one","chat":"room@chatroom","cursor":{"message_0.db":1},"salts":{"message_0.db":"0123456789abcdef0123456789abcdef"}},"has_more":false}
            """;
    }

    public static class FixtureHost {
        public static void main(String[] args) throws Exception {
            Path configuration = Path.of(args[0]);
            var worker = new HistoryWorker(JSON.readValue(Files.readString(configuration), Map.class));
            worker.start();
            var closer = new Thread(() -> {
                try {
                    Thread.sleep(1200);
                    worker.close();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            closer.setDaemon(true);
            closer.start();
            Files.writeString(configuration.resolveSibling("host-started"), "started");
        }
    }

    public static class FixtureCli {
        public static void main(String[] args) throws Exception {
            if (args.length != 11 || !"--config".equals(args[0]) || !"history".equals(args[2]) || !"卧底不追高".equals(args[3]) || !"--structured".equals(args[6]) || !"--cursor-file".equals(args[7]) || !"200".equals(args[10])) System.exit(3);
            Path config = Path.of(args[1]);
            Path marker = config.resolveSibling("invoked.tmp");
            Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
            Files.move(marker, config.resolveSibling("invoked"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(Path.of(args[8]), config.resolveSibling("seen-cursor"), StandardCopyOption.REPLACE_EXISTING);
            String value = Files.readString(config);
            if (value.equals("FAIL")) {
                System.err.println("SECRET sensitive stderr");
                System.exit(4);
            }
            if (value.equals("SLEEP")) Thread.sleep(60000);
            if (value.equals("LARGE")) value = "x".repeat(5 * 1024 * 1024);
            System.out.print(value);
        }
    }
}
