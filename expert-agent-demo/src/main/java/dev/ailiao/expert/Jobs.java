package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import reactor.core.publisher.Sinks;

/** 有上限的后台任务；取消和完成共用锁，迟到结果不能写入。 */
final class Jobs implements AutoCloseable {
    interface Work { ObjectNode run(Job job) throws Exception; }
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private boolean closed;
    /** 启动任务并限制并发。@param kind 任务种类 @param key 防止同会话并发 @param work 实际工作 */
    synchronized Job start(String kind, String key, Work work) {
        if (closed) { throw new IllegalStateException("任务服务已关闭"); }
        if (jobs.values().stream().filter(j -> j.running()).count() >= 2) { throw new IllegalArgumentException("已有两个任务运行，请等待或取消后重试"); }
        if (!key.isBlank() && jobs.values().stream().anyMatch(j -> j.running() && key.equals(j.key))) { throw new IllegalArgumentException("该会话已有任务运行"); }
        while (jobs.size() >= 40) {
            String remove = jobs.entrySet().stream().filter(e -> !e.getValue().running()).map(Map.Entry::getKey).findFirst().orElseThrow();
            jobs.remove(remove);
        }
        Job job = new Job(kind, key); jobs.put(job.id, job);
        job.future = workers.submit(() -> {
            try { ObjectNode result = work.run(job); job.done(result); }
            catch (Exception error) { job.fail(error); }
        });
        return job;
    }
    /** 读取任务。@param id 任务身份 */
    synchronized Job get(String id) {
        Job job = jobs.get(id);
        if (job == null) { throw new IllegalArgumentException("任务不存在，服务重启后请重新执行"); }
        return job;
    }
    synchronized ArrayNode snapshots() {
        ArrayNode values = Json.array(); jobs.values().forEach(j -> values.add(j.snapshot())); return values;
    }
    /** 停止所有任务，关闭插件资源。 */
    @Override public void close() {
        synchronized (this) {
            if (!closed) { closed = true; jobs.values().forEach(Job::cancel); workers.shutdownNow(); }
        }
        // Cancellation marks job state immediately; journal/finally cleanup still runs on the worker.
        // Release the Jobs monitor before joining, since cleanup may need it.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean interrupted = false;
        try {
            while (!workers.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { throw new IllegalStateException("后台任务未在关闭时限内停止"); }
                try { workers.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    static final class Job {
        final String id = Json.id();
        final String key;
        final long deadline;
        final Sinks.Empty<Void> stop = Sinks.empty();
        final ObjectNode state;
        volatile Future<?> future;
        private volatile boolean cancelled;
        private boolean committed;
        /** 建立任务快照。@param kind 类型 @param key 并发身份 */
        Job(String kind, String key) {
            this.key = key;
            deadline = System.nanoTime() + Duration.ofMinutes(kind.equals("import") ? 30 : kind.equals("generate") ? 20 : 6).toNanos();
            state = Json.object().put("id", id).put("kind", kind).put("status", "running").put("createdAt", Instant.now().toString());
            state.set("events", Json.array());
        }
        /** 读取状态而不暴露内部可变对象。 */
        synchronized ObjectNode snapshot() { return state.deepCopy(); }
        /** 判断是否仍占用任务位置。 */
        synchronized boolean running() { return state.path("status").asText().equals("running"); }
        /** 供模型与工具观察取消和总超时。 */
        boolean stopped() { return cancelled || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted(); }
        /** 阻止超时或取消后的继续执行。 */
        void check() {
            if (stopped()) { throw new CancellationException(cancelled ? "任务已取消" : "任务达到总时限"); }
        }
        /** 计算当前调用可等待的剩余时间。@param seconds 单次上限 */
        Duration remaining(int seconds) { check(); return Duration.ofNanos(Math.max(1, Math.min(Duration.ofSeconds(seconds).toNanos(), deadline - System.nanoTime()))); }
        /** 增加可见的实际进度，不输出模型内部思考。@param phase 阶段 @param message 描述 */
        synchronized void event(String phase, String message) {
            check();
            if (state.path("events").size() < 100) { state.withArray("events").add(Json.object().put("phase", phase).put("message", message).put("at", Instant.now().toString())); }
            state.put("phase", phase).put("message", message);
        }
        /** 保存最终结果，锁内检查防止取消后的落盘。@param action 实际提交操作 */
        synchronized ObjectNode commit(Callable<ObjectNode> action) throws Exception {
            check(); ObjectNode result = action.call(); committed = true; state.put("status", "completed"); state.set("result", result); return result;
        }
        /** 没有业务落盘的任务也可完成。@param result 返回结果 */
        synchronized void done(ObjectNode result) { if (!committed) { check(); state.put("status", "completed"); state.set("result", result); } }
        /** 失败时不把供应商原始请求或凭据送到浏览器。@param error 异常 */
        synchronized void fail(Exception error) {
            if (committed) { return; }
            state.put("status", cancelled || error instanceof CancellationException ? "cancelled" : "failed");
            String message = error instanceof IllegalArgumentException || error instanceof CancellationException ? error.getMessage() : "处理失败（" + error.getClass().getSimpleName() + "），请检查模型连接或缩小资料后重试";
            if (message == null || message.length() > 180) { message = "处理失败，请检查资料格式或模型连接后重试"; }
            state.put("error", message); stop.tryEmitEmpty();
        }
        /** 取消订阅和工作线程，已提交任务保持完成。 */
        synchronized void cancel() {
            if (!running() || committed) { return; }
            cancelled = true; state.put("status", "cancelled").put("error", "任务已取消"); stop.tryEmitEmpty();
            if (future != null) { future.cancel(true); }
        }
    }
}
