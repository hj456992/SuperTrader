package dev.garden.feishu;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Function;

/** One explicit capture per get(); no polling, persistence, shell or chat mutations. */
public final class FeishuCapture implements Supplier<Map<String,Object>>, Function<Map<String,Object>,Map<String,Object>>, AutoCloseable {
    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private static final Set<String> UNAVAILABLE = Set.of("permission_required", "not_running", "no_chat", "empty", "error", "not_found", "ambiguous");
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final List<String> command;
    private final int timeoutSeconds;
    private final AtomicBoolean capturing = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private volatile boolean closed;
    private Process active;
    private final ExecutorService outputReader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "feishu-capture-output");
        thread.setDaemon(true);
        return thread;
    });

    public FeishuCapture(Map<?,?> config) {
        Object prefix = config.get("commandPrefix");
        if (!(prefix instanceof List<?> values) || values.isEmpty() || values.stream().anyMatch(
                value -> !(value instanceof String text) || text.isBlank() || text.contains("\0"))) {
            throw new IllegalArgumentException("commandPrefix must be a nonempty string array");
        }
        command = values.stream().map(String.class::cast).toList();
        if (!Path.of(command.get(0)).isAbsolute()) throw new IllegalArgumentException("command executable must be absolute");
        Object timeout = config.containsKey("timeoutSeconds") ? config.get("timeoutSeconds") : 20;
        if (!(timeout instanceof Number value) || value.doubleValue() != value.intValue()
                || value.intValue() < 1 || value.intValue() > 30) {
            throw new IllegalArgumentException("timeoutSeconds must be an integer from 1 to 30");
        }
        timeoutSeconds = ((Number)timeout).intValue();
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build());
    }

    @Override public Map<String,Object> get() {
        return execute(command, false, null);
    }

    @Override public Map<String,Object> apply(Map<String,Object> request) {
        if (request == null) return error("飞书会话请求无效。");
        List<String> arguments = new ArrayList<>(command);
        if ("list".equals(request.get("action")) && request.keySet().equals(Set.of("action"))) {
            arguments.add("list");
            return execute(arguments, true, null);
        }
        if (!"select".equals(request.get("action"))
                || !request.keySet().equals(Set.of("action", "chatId", "chatTitle"))
                || !(request.get("chatId") instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,160}")
                || !boundedString(request.get("chatTitle"),100)
                || ((String)request.get("chatTitle")).contains("\0")) {
            return error("飞书会话请求无效。");
        }
        String title = (String)request.get("chatTitle");
        arguments.addAll(List.of("select", id, title));
        return execute(arguments, false, title);
    }

    private Map<String,Object> execute(List<String> arguments, boolean list, String expectedTitle) {
        if (closed) return error("飞书读取插件已关闭。");
        if (!capturing.compareAndSet(false,true)) return Map.of("status","busy","detail","正在读取当前会话，请稍候。");
        Process process = null;
        Future<byte[]> output = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            synchronized (lifecycle) {
                if (closed) return error("飞书读取插件已关闭。");
                process = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                active = process;
                process.getOutputStream().close();
            }
            Process child = process;
            output = outputReader.submit(() -> boundedRead(child.getInputStream()));
            byte[] bytes = output.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            if (closed) return error("飞书读取插件已关闭。");
            if (process.exitValue() != 0) return error("飞书读取失败，请检查客户端后重试。");
            return validate(bytes, list, expectedTitle);
        } catch (TimeoutException failure) {
            return error("读取飞书超时，请重试。");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return error("飞书读取已中断。");
        } catch (Exception failure) {
            return error("无法读取飞书当前会话，请检查客户端后重试。");
        } finally {
            if (process != null) terminate(process);
            if (output != null) output.cancel(true);
            synchronized (lifecycle) { if (active == process) active = null; }
            capturing.set(false);
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }

    private static byte[] boundedRead(InputStream stream) throws IOException {
        try (stream; var result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (result.size() + count > OUTPUT_LIMIT) throw new IOException("output limit");
                result.write(buffer,0,count);
            }
            return result.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> validate(byte[] bytes, boolean list, String expectedTitle) throws IOException {
        Object parsed = json.readValue(bytes,Object.class);
        if (!(parsed instanceof Map<?,?> data)) return error("飞书读取结果格式无效。");
        Object status = data.get("status");
        if (status instanceof String text && UNAVAILABLE.contains(text)) {
            Object detail = data.get("detail");
            if (!(detail instanceof String message) || message.isBlank() || message.length() > 500)
                return error("飞书读取结果格式无效。");
            return Map.of("status",text,"detail",message);
        }
        if (list) return validateChats(data);
        if (!"ok".equals(status) || !"feishu".equals(data.get("platform")) || !"current_view".equals(data.get("scope"))
                || !boundedString(data.get("chatTitle"),500) || !boundedString(data.get("text"),16000)
                || !(data.get("messages") instanceof List<?> messages) || messages.isEmpty() || messages.size() > 60
                || messages.stream().anyMatch(message -> !(message instanceof Map<?,?>))) {
            return error("飞书读取结果格式无效。");
        }
        if (expectedTitle != null && !expectedTitle.equals(data.get("chatTitle")))
            return error("飞书当前会话与所选群聊不一致，请重试。");
        try { Instant.parse((String)data.get("capturedAt")); }
        catch (RuntimeException invalid) { return error("飞书读取结果时间无效。"); }
        if ((data.containsKey("truncated") && !(data.get("truncated") instanceof Boolean))
                || (data.containsKey("detail") && !boundedString(data.get("detail"),500))) {
            return error("飞书读取结果格式无效。");
        }
        // Whitelist the public protocol, keeping the class-loader boundary entirely in JDK types.
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("status","platform","chatTitle","text","capturedAt","messages","scope")) result.put(key,data.get(key));
        result.put("truncated",data.containsKey("truncated") ? data.get("truncated") : Boolean.FALSE);
        if (data.containsKey("detail")) result.put("detail",data.get("detail"));
        return result;
    }

    private Map<String,Object> validateChats(Map<?,?> data) {
        if (!"ok".equals(data.get("status")) || !"feishu".equals(data.get("platform"))
                || !"loaded_chats".equals(data.get("scope")) || !boundedString(data.get("detail"),500)
                || !(data.get("chats") instanceof List<?> chats) || chats.size() > 200)
            return error("飞书会话列表格式无效。");
        Set<String> ids = new HashSet<>();
        List<Map<String,Object>> choices = new ArrayList<>();
        for (Object value : chats) {
            if (!(value instanceof Map<?,?> chat) || !(chat.get("id") instanceof String id)
                    || !id.matches("[A-Za-z0-9_-]{1,160}") || !ids.add(id)
                    || !boundedString(chat.get("title"),100))
                return error("飞书会话列表格式无效。");
            choices.add(Map.of("id",id,"title",chat.get("title")));
        }
        return Map.of("status","ok","platform","feishu","scope","loaded_chats","chats",choices,"detail",data.get("detail"));
    }
    private static boolean boundedString(Object value, int limit) {
        return value instanceof String text && !text.isBlank() && text.length() <= limit;
    }
    private static Map<String,Object> error(String detail) { return Map.of("status","error","detail",detail); }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle child : descendants) child.destroyForcibly();
        process.destroyForcibly();
        try { process.waitFor(500,TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        for (ProcessHandle child : descendants) if (child.isAlive()) child.destroyForcibly();
        try { process.getInputStream().close(); } catch (IOException ignored) { }
    }
    @Override public void close() {
        synchronized (lifecycle) {
            closed = true;
            if (active != null) terminate(active);
        }
        outputReader.shutdownNow();
    }
}
