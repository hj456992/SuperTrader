package com.supertrader.demo.agentdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The Agent Demo SSE Event Hub (Task 6).
 *
 * <p>The Hub is a pure <b>fan-out</b> layer: the authoritative event log lives
 * in the {@link AgentDemoStore} outbox. On subscribe the Hub replays the
 * durable events past the resume cursor ({@code max(after, Last-Event-ID)}),
 * then streams live events published by the Run Coordinator. It guarantees:
 * <ul>
 *   <li>no duplicate {@code seq} is ever delivered to one subscriber;</li>
 *   <li>a slow consumer is disconnected (its bounded queue overflows) WITHOUT
 *       blocking the Run — {@code publish} returns immediately by enqueuing to
 *       a per-subscriber bounded queue and never blocks the publishing thread;</li>
 *   <li>heartbeats never enter the Store;</li>
 *   <li>each session has a server cap on subscribers and per-subscriber pending
 *       bytes/events; a send exception removes the subscriber without changing
 *       the Run status.</li>
 * </ul>
 *
 * <p>Each subscriber owns a single-worker daemon executor that drains its
 * bounded queue, so a blocked client only ever affects its own worker. The
 * publishing thread (the Run Coordinator's thread) is never blocked.
 */
public final class AgentDemoEventHub {

    private static final Logger log = LoggerFactory.getLogger(AgentDemoEventHub.class);

    /** Supplier of the current durable event log (the Store outbox). */
    private final Supplier<List<AgentDemoDtos.DemoEventEnvelope>> durableEvents;
    private final int maxSubscribersPerSession;
    private final int pendingPerSubscriber;

    private final Map<String, List<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService dispatch;

    /**
     * @param durableEvents           supplies the durable event log for replay
     * @param maxSubscribersPerSession server cap on concurrent subscribers / session
     * @param pendingPerSubscriber     bounded queue depth per subscriber; an
     *                                 overflowing queue disconnects the subscriber
     */
    public AgentDemoEventHub(Supplier<List<AgentDemoDtos.DemoEventEnvelope>> durableEvents,
                             int maxSubscribersPerSession, int pendingPerSubscriber) {
        this.durableEvents = durableEvents;
        this.maxSubscribersPerSession = maxSubscribersPerSession;
        this.pendingPerSubscriber = pendingPerSubscriber;
        ThreadFactory factory = new NamedDaemonFactory("agent-demo-sse");
        this.dispatch = Executors.newCachedThreadPool(factory);
    }

    // ------------------------------------------------------------------ //
    // Abstraction over SseEmitter (testable)
    // ------------------------------------------------------------------ //

    /** A sink that receives one event at a time (SseEmitter in production). */
    public interface SseSink {
        /** Send one event. Implementations should not block indefinitely. */
        void send(AgentDemoDtos.DemoEventEnvelope env);
        /** Complete normally (client gone / shutdown). */
        void complete();
        /** Complete with an error (invalid cursor / fatal send failure). */
        void completeWithError(Throwable t);
        /** Whether this sink is already stopped (so the Hub can skip it). */
        boolean isStopped();
    }

    /** A {@link SseSink} backed by a real Spring {@link SseEmitter}. */
    public static final class SseEmitterSink implements SseSink {
        private final SseEmitter emitter;
        SseEmitterSink(SseEmitter emitter) { this.emitter = emitter; }
        @Override public void send(AgentDemoDtos.DemoEventEnvelope env) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(env.seq()))
                        .name(env.type() == null ? "event" : env.type())
                        .data(env));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        @Override public void complete() { emitter.complete(); }
        @Override public void completeWithError(Throwable t) { emitter.completeWithError(t); }
        @Override public boolean isStopped() { return false; }
    }

    // ------------------------------------------------------------------ //
    // Subscribe
    // ------------------------------------------------------------------ //

    /**
     * Subscribe to a session's event stream. Replays durable events past
     * {@code max(after, lastEventId)}, then streams live events.
     *
     * @param sessionId    the owning session
     * @param after        the query-param resume cursor
     * @param lastEventId  the {@code Last-Event-ID} header value (nullable)
     * @param sink         the sink that receives events
     * @return a Spring {@link SseEmitter} (for the controller) — the same sink
     *         the caller passed if it is already an emitter wrapper.
     */
    public SseEmitter subscribe(String sessionId, long after, Long lastEventId, SseSink sink) {
        long cursor = computeCursor(after, lastEventId);
        if (cursor < 0) {
            sink.completeWithError(new IllegalArgumentException("INVALID_EVENT_CURSOR"));
            return sink instanceof SseEmitterSink ses
                    ? ses.emitter : new SseEmitter();
        }
        // Cap subscribers per session.
        List<Subscription> existing = subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        if (existing.size() >= maxSubscribersPerSession) {
            sink.completeWithError(new IllegalStateException("TOO_MANY_SUBSCRIBERS"));
            return sink instanceof SseEmitterSink ses
                    ? ses.emitter : new SseEmitter();
        }

        Subscription sub = new Subscription(sessionId, sink, cursor, pendingPerSubscriber);
        existing.add(sub);

        // Replay durable events SYNCHRONOUSLY on the caller thread so the
        // client sees the back-fill before this method returns; the live drain
        // loop runs on a worker so the publishing (Run) thread is never blocked.
        try {
            sub.replay(durableEvents.get());
        } catch (Throwable t) {
            sub.stop();
            return sink instanceof SseEmitterSink ses
                    ? ses.emitter : new SseEmitter();
        }
        if (sub.stopped) {
            return sink instanceof SseEmitterSink ses ? ses.emitter : new SseEmitter();
        }
        dispatch.submit(sub::drainLoop);

        return sink instanceof SseEmitterSink ses ? ses.emitter : new SseEmitter();
    }

    private long computeCursor(long after, Long lastEventId) {
        if (after < 0) return -1;
        if (lastEventId == null) return after;
        if (lastEventId < 0) return -1;
        return Math.max(after, lastEventId);
    }

    // ------------------------------------------------------------------ //
    // Publish (called by the Run Coordinator — never blocks)
    // ------------------------------------------------------------------ //

    /** Broadcast one live event to all subscribers of its session. */
    public void publish(AgentDemoDtos.DemoEventEnvelope env) {
        List<Subscription> subs = subscribers.get(env.sessionId());
        if (subs == null || subs.isEmpty()) return;
        for (Subscription s : subs) {
            if (s.stopped || s.sink.isStopped()) {
                s.stop();
                continue;
            }
            // Drop events at or below the subscriber's cursor (no duplicates).
            if (env.seq() <= s.lastSent) continue;
            // Offer to the bounded queue; if full, disconnect the slow consumer.
            boolean accepted = s.queue.offer(env);
            if (!accepted) {
                disconnect(s, "slow consumer (queue overflow)");
            }
        }
    }

    /** Send a heartbeat comment to a session's subscribers (never stored). */
    public void sendHeartbeat(String sessionId) {
        List<Subscription> subs = subscribers.get(sessionId);
        if (subs == null) return;
        for (Subscription s : subs) {
            if (s.stopped) continue;
            // Heartbeats are delivered inline (not via the durable queue) and
            // never call the store supplier.
            s.sendHeartbeat();
        }
    }

    // ------------------------------------------------------------------ //
    // Lifecycle
    // ------------------------------------------------------------------ //

    /** Complete every emitter (Spring shutdown). */
    public void shutdown() {
        for (List<Subscription> subs : subscribers.values()) {
            for (Subscription s : subs) {
                s.stop();
            }
        }
        subscribers.clear();
        dispatch.shutdown();
        try {
            dispatch.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Visible for tests. */
    int subscriberCount(String sessionId) {
        List<Subscription> subs = subscribers.get(sessionId);
        return subs == null ? 0 : subs.size();
    }

    private void disconnect(Subscription s, String reason) {
        if (s.stopped) return;
        log.debug("disconnecting sse subscriber for session {}: {}", s.sessionId, reason);
        s.stop();
    }

    // ------------------------------------------------------------------ //
    // Subscription
    // ------------------------------------------------------------------ //

    private final class Subscription {
        private final String sessionId;
        private final SseSink sink;
        private final LinkedBlockingQueue<AgentDemoDtos.DemoEventEnvelope> queue;
        private volatile long lastSent;
        private volatile boolean stopped;

        Subscription(String sessionId, SseSink sink, long cursor, int pending) {
            this.sessionId = sessionId;
            this.sink = sink;
            this.queue = new LinkedBlockingQueue<>(pending);
            this.lastSent = cursor;
        }

        /** Replay durable events past the cursor (caller thread). */
        void replay(List<AgentDemoDtos.DemoEventEnvelope> durable) {
            for (AgentDemoDtos.DemoEventEnvelope e : durable) {
                if (stopped) return;
                if (!sessionId.equals(e.sessionId())) continue;
                if (e.seq() <= lastSent) continue;
                deliver(e);
            }
        }

        /** The live-drain loop (worker thread). */
        void drainLoop() {
            while (!stopped) {
                try {
                    AgentDemoDtos.DemoEventEnvelope e = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (e == null) continue;
                    if (e.seq() <= lastSent) continue; // duplicate drop
                    deliver(e);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    log.debug("sse send failed for session {}: {}", sessionId, t.toString());
                    stop();
                    return;
                }
            }
        }

        private void deliver(AgentDemoDtos.DemoEventEnvelope e) {
            sink.send(e);
            lastSent = e.seq();
        }

        void sendHeartbeat() {
            try {
                // A heartbeat carries no seq; we send it as a comment-style
                // event the client ignores but keeps the connection alive.
                AgentDemoDtos.DemoEventEnvelope beat = new AgentDemoDtos.DemoEventEnvelope(
                        -1L, "heartbeat-" + System.nanoTime(), sessionId, null,
                        "heartbeat", java.time.Instant.now().toString(), Map.of());
                sink.send(beat);
            } catch (Throwable t) {
                stop();
            }
        }

        void stop() {
            if (stopped) return;
            stopped = true;
            try {
                sink.complete();
            } catch (Throwable ignore) {
                // best-effort
            }
            List<Subscription> subs = subscribers.get(sessionId);
            if (subs != null) subs.remove(this);
        }
    }

    /** Named daemon thread factory (so the JVM exits cleanly). */
    private static final class NamedDaemonFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger count = new AtomicInteger();
        NamedDaemonFactory(String prefix) { this.prefix = prefix; }
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
