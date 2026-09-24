package dev.garden.wechat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** 子进程独立拥有者；无 shell、无 stderr 记录，并限制执行时间和 stdout 大小。 */
final class CliProcess implements AutoCloseable {
    private final HistoryConfig config;
    private Process active;
    private boolean closed;
    static final int OUTPUT_LIMIT = 4 * 1024 * 1024;

    CliProcess(HistoryConfig config) { this.config = config; }

    /** 读取完整成功响应；任何退出码、超时或读取失败均不返回部分消息。 */
    byte[] execute(Path cursor) throws Exception {
        var command = new ArrayList<>(config.commandPrefix());
        command.addAll(List.of("--config", config.cliConfig().toString(), "history", HistoryConfig.TARGET,
                "--format", "json", "--structured", "--cursor-file", cursor.toString(), "--limit", "200"));
        Process process;
        synchronized (this) {
            if (closed) throw new IOException("closed");
            var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("LANG", "en_US.UTF-8");
            builder.environment().put("LC_ALL", "en_US.UTF-8");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            active = process;
        }
        process.getOutputStream().close();
        var reader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "wechat-history-stdout");
            thread.setDaemon(true);
            return thread;
        });
        Future<byte[]> output = reader.submit(() -> {
            try (var input = process.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int size;
                while ((size = input.read(chunk)) != -1) {
                    if (bytes.size() + size > OUTPUT_LIMIT) {
                        terminate(process);
                        throw new IOException("output_limit");
                    }
                    bytes.write(chunk, 0, size);
                }
                return bytes.toByteArray();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
        try {
            if (!process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS)) throw new IOException("cli_timeout");
            if (process.exitValue() != 0) throw new IOException("cli_failed");
            return output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally {
            terminate(process);
            output.cancel(true);
            reader.shutdownNow();
            synchronized (this) {
                if (active == process) active = null;
            }
        }
    }

    /** 只终止本插件创建的子进程及其后代；等待父进程退出后才交还生命周期。 */
    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (active != null) terminate(active);
    }
}
