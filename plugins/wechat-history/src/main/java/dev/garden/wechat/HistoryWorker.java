package dev.garden.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** 插件实例的完整资源范围，持有目录锁、轮询器、HTTP 连接和唯一 CLI 子进程。 */
final class HistoryWorker implements AutoCloseable {
    private final HistoryConfig config;
    private final CliProcess cli;
    private final LogbookBridge bridge;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock tickLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, Object> status = new ConcurrentHashMap<>();
    private JsonNode pendingBatch;

    /** 私有目录创建后立即加锁；失败时不残留文件句柄或后台线程。 */
    HistoryWorker(Map<?, ?> values) throws Exception {
        config = HistoryConfig.from(values);
        Files.createDirectories(config.dataDir(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(config.dataDir())) throw new IOException("symlink_data_directory");
        Files.setPosixFilePermissions(config.dataDir(), PosixFilePermissions.fromString("rwx------"));
        Path lockPath = config.dataDir().resolve("collector.lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            directoryLock = lockChannel.tryLock();
            if (directoryLock == null) throw new IOException("collector_already_running");
            Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception error) {
            lockChannel.close();
            throw new IOException("collector_already_running");
        }
        cli = new CliProcess(config);
        bridge = new LogbookBridge(config);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "wechat-history-poll");
            thread.setDaemon(false);
            return thread;
        });
        status.put("source", "wechat_cli_history");
        status.put("status", "starting");
        status.put("detail", "waiting_for_first_poll");
    }

    /** 登记到 context.own 后再启动，确保初始化后续失败也可以释放资源。 */
    void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 0, config.intervalSeconds(), TimeUnit.SECONDS);
    }

    /** 对外只读 JDK Map，不让插件类加载器类型渗入公共服务合同。 */
    Map<String, Object> statusView() { return Collections.unmodifiableMap(status); }

    /** 每轮只处理一个有限批次；提交失败时保留原批次，绝不提前获取新检查点。 */
    void tick() {
        tickLock.lock();
        try {
            if (closed.get()) return;
            if (!Files.isRegularFile(config.keyFile()) || !Files.isReadable(config.keyFile()) || Files.size(config.keyFile()) == 0) {
                publishStatus("setup_required", "wechat_key_required");
                return;
            }
            if (pendingBatch == null) {
                JsonNode response = bridge.request("/v1/history-checkpoint", null);
                if (response == null || !response.isObject() || !response.has("checkpoint")) {
                    throw new IOException("invalid_checkpoint_response");
                }
                JsonNode checkpoint = response.get("checkpoint");
                if (!checkpoint.isNull()) HistoryProtocol.validateCheckpoint(checkpoint);
                Path cursor = writeCursor(checkpoint);
                JsonNode batch = LogbookBridge.JSON.readTree(cli.execute(cursor));
                pendingBatch = HistoryProtocol.validate(batch, checkpoint);
            }
            if (closed.get()) return;
            bridge.request("/v1/history-batch", pendingBatch);
            boolean hasMore = pendingBatch.path("has_more").booleanValue();
            pendingBatch = null;
            publishStatus(hasMore ? "syncing" : "connected", "history_batch_committed");
        } catch (Exception error) {
            if (!closed.get()) publishStatus("error", "history_collection_failed");
        } finally {
            tickLock.unlock();
        }
    }

    /** 游标文件只包含服务已提交的状态，以原子替换防止半写文件被 CLI 读取。 */
    private Path writeCursor(JsonNode checkpoint) throws Exception {
        Path temporary = Files.createTempFile(config.dataDir(), "cursor-", ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Path destination = config.dataDir().resolve("cursor.json");
        try {
            Files.write(temporary, LogbookBridge.JSON.writeValueAsBytes(checkpoint));
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 状态字段固定且不含异常消息，避免输出消息正文、令牌或 CLI stderr。 */
    private void publishStatus(String state, String detail) {
        if (closed.get()) return;
        status.put("status", state);
        status.put("detail", detail);
        status.put("updatedAt", java.time.Instant.now().toString());
        try {
            bridge.request("/v1/history-status", LogbookBridge.JSON.valueToTree(Map.of("status", state, "detail", detail)));
        } catch (Exception ignored) {
            status.put("delivery", "logbook_unreachable");
            return;
        }
        status.remove("delivery");
    }

    /** 阻止新轮询、结束本实例子进程并等待当前轮结束，最后释放跨进程目录锁。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        cli.close();
        bridge.close();
        tickLock.lock();
        try {
            pendingBatch = null;
            status.put("status", "stopped");
            directoryLock.release();
            lockChannel.close();
        } catch (IOException ignored) {
            // 操作系统会在进程结束时兜底回收；不打印内部路径或数据。
        } finally {
            tickLock.unlock();
        }
    }
}
