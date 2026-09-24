package dev.garden.feishu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FeishuCaptureTest {
    @TempDir Path directory;
    static final String SUCCESS = "{\"status\":\"ok\",\"platform\":\"feishu\",\"chatTitle\":\"测试群\",\"text\":\"对方：消息\",\"capturedAt\":\"2026-09-23T01:00:00Z\",\"messages\":[{\"sender\":\"张三\",\"text\":\"消息\",\"role\":\"them\"}],\"scope\":\"current_view\"}";

    Map<String,Object> config(String script, int timeout) throws Exception {
        Path file = directory.resolve("collector.py");
        Files.writeString(file, script);
        return Map.of("commandPrefix", List.of("/usr/bin/python3", file.toString()), "timeoutSeconds", timeout);
    }
    String printJson(String json) { return "print('''" + json + "''')\n"; }
    String sleeper() {
        return "import os,time,subprocess,pathlib\n" +
            "child=subprocess.Popen(['/usr/bin/python3','-c','import time; time.sleep(60)'])\n" +
            "p=pathlib.Path(__file__).with_name('pids')\n" +
            "p.with_suffix('.tmp').write_text(str(os.getpid())+','+str(child.pid))\n" +
            "p.with_suffix('.tmp').replace(p)\ntime.sleep(60)\n";
    }
    List<Long> awaitPids() throws Exception {
        Path marker = directory.resolve("pids");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(marker), "collector never started");
        return Arrays.stream(Files.readString(marker).split(",")).map(Long::parseLong).toList();
    }
    void assertDead(List<Long> pids) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (pids.stream().anyMatch(p -> ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false)) && System.nanoTime() < deadline) Thread.sleep(10);
        for (long pid : pids) assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process remains alive: " + pid);
    }

    @Test void successfulCaptureReturnsJdkMapAndFreshSnapshotOnEachCall() throws Exception {
        try (var capture = new FeishuCapture(config(printJson(SUCCESS), 5))) {
            Map<String,Object> first = capture.get();
            assertEquals("ok", first.get("status"));
            assertEquals("测试群", first.get("chatTitle"));
            assertEquals("对方：消息", first.get("text"));
            assertInstanceOf(Map.class, ((List<?>)first.get("messages")).get(0));
            Files.writeString(directory.resolve("collector.py"), printJson(SUCCESS.replace("消息", "新快照")));
            assertEquals("对方：新快照", capture.get().get("text"));
        }
    }
    @Test void preservesTruncationAndSuccessDetail() throws Exception {
        String value = SUCCESS.replace("\"scope\":\"current_view\"", "\"scope\":\"current_view\",\"truncated\":true,\"detail\":\"已截取最近可见消息\"");
        try (var capture = new FeishuCapture(config(printJson(value), 5))) {
            var result = capture.get();
            assertEquals("ok", result.get("status"));
            assertEquals(true, result.get("truncated"));
            assertEquals("已截取最近可见消息", result.get("detail"));
        }
        try (var capture = new FeishuCapture(config(printJson(SUCCESS), 5))) {
            assertEquals(false, capture.get().get("truncated"));
        }
    }
    @Test void rejectsInvalidTruncationAndUnboundedSuccessDetail() throws Exception {
        for (String fields : List.of("\"truncated\":\"true\"", "\"truncated\":null", "\"detail\":42", "\"detail\":\"" + "x".repeat(501) + "\"")) {
            String value = SUCCESS.substring(0,SUCCESS.length()-1) + "," + fields + "}";
            try (var capture = new FeishuCapture(config(printJson(value), 5))) {
                assertEquals("error", capture.get().get("status"));
            }
        }
    }
    @Test void constructionDoesNotCaptureUntilGet() throws Exception {
        try (var capture = new FeishuCapture(config("import pathlib\npathlib.Path(__file__).with_name('pids').write_text('invoked')\n", 5))) {
            assertFalse(Files.exists(directory.resolve("pids")));
        }
    }
    @Test void forwardsStructuredUnavailableStatuses() throws Exception {
        for (String status : List.of("permission_required", "not_running", "no_chat", "empty", "error")) {
            try (var capture = new FeishuCapture(config(printJson("{\"status\":\"" + status + "\",\"detail\":\"请检查当前会话\"}"), 5))) {
                assertEquals(Map.of("status",status,"detail","请检查当前会话"), capture.get());
            }
        }
    }
    @Test void timeoutStopsParentAndDescendant() throws Exception {
        try (var capture = new FeishuCapture(config(sleeper(), 1))) {
            long start = System.nanoTime();
            Map<String,Object> result = capture.get();
            assertEquals("error", result.get("status"));
            assertTrue(result.containsKey("detail"));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
            assertDead(awaitPids());
        }
    }
    @Test void closeStopsActiveCaptureAndPreventsRestart() throws Exception {
        var capture = new FeishuCapture(config(sleeper(), 30));
        var task = new FutureTask<>(capture::get);
        Thread thread = new Thread(task);
        try {
            thread.start();
            List<Long> pids = awaitPids();
            capture.close();
            assertEquals("error", task.get(3,TimeUnit.SECONDS).get("status"));
            assertDead(pids);
            Files.delete(directory.resolve("pids"));
            assertEquals("error", capture.get().get("status"));
            assertFalse(Files.exists(directory.resolve("pids")));
        } finally { capture.close(); thread.join(3000); }
    }
    @Test void overlappingCaptureReturnsBusyWithoutLaunchingAnotherChild() throws Exception {
        try (var capture = new FeishuCapture(config(sleeper(), 30))) {
            var task = new FutureTask<>(capture::get);
            new Thread(task).start();
            awaitPids();
            assertEquals("busy", capture.get().get("status"));
            capture.close();
            task.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void oversizedOutputStopsPromptlyWithoutWaitingForTimeout() throws Exception {
        try (var capture = new FeishuCapture(config("import sys,time\nsys.stdout.write('x'*(1024*1024+1))\nsys.stdout.flush()\ntime.sleep(60)\n", 30))) {
            long start = System.nanoTime();
            Map<String,Object> result = capture.get();
            assertEquals("error", result.get("status"));
            assertTrue(result.containsKey("detail"));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        }
    }
    @Test void nonzeroExitNeverLeaksStderrOrAcceptsValidStdout() throws Exception {
        try (var capture = new FeishuCapture(config(printJson(SUCCESS) + "import sys\nsys.stderr.write('SECRET_TOKEN')\nsys.exit(2)\n", 5))) {
            var result = capture.get();
            assertEquals("error", result.get("status"));
            assertTrue(result.containsKey("detail"));
            assertFalse(result.toString().contains("SECRET_TOKEN"));
        }
    }
    @Test void rejectsMalformedOrUnboundedSuccess() throws Exception {
        List<String> invalid = List.of("not json", SUCCESS.replace("测试群", " "), SUCCESS.replace("对方：消息", ""),
            SUCCESS.replace("2026-09-23T01:00:00Z", "not-time"), SUCCESS.replace("current_view", "all"),
            SUCCESS.replace("feishu", "wechat"), SUCCESS.replace("对方：消息", "x".repeat(16001)),
            SUCCESS.replace("[{\"sender\":\"张三\",\"text\":\"消息\",\"role\":\"them\"}]", "[]"),
            SUCCESS.replace("[{\"sender\":\"张三\",\"text\":\"消息\",\"role\":\"them\"}]", "[" + String.join(",", Collections.nCopies(61, "{}")) + "]"),
            SUCCESS + " {}", "{\"status\":\"unexpected\",\"detail\":\"x\"}");
        for (String value : invalid) {
            try (var capture = new FeishuCapture(config(printJson(value), 5))) {
                var result = capture.get();
                assertEquals("error", result.get("status"));
                assertTrue(result.containsKey("detail"));
            }
        }
    }
    @Test void rejectsUnsafeConfigBeforeStartingProcess() {
        for (Object prefix : List.of(List.of("python3", "collector.py"), List.of(), List.of("/usr/bin/python3", "\0")))
            assertThrows(IllegalArgumentException.class, () -> new FeishuCapture(Map.of("commandPrefix",prefix)));
        for (Object timeout : List.of(0,31,1.5,"20"))
            assertThrows(IllegalArgumentException.class, () -> new FeishuCapture(Map.of("commandPrefix",List.of("/usr/bin/python3"),"timeoutSeconds",timeout)));
    }
}
