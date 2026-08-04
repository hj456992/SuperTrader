package com.supertrader.demo.agentdemo;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6 tests for the SSE Event Hub.
 *
 * <p>Verifies the fan-out / replay / resume contract: the Hub replays durable
 * events past {@code after} on subscribe, then streams live events; there are
 * no duplicate seq values; a disconnect cleans up the emitter; heartbeats never
 * enter the Store; a slow consumer is disconnected WITHOUT blocking the Run;
 * and reconnection honours both the {@code after} query param and the
 * {@code Last-Event-ID} header (taking the larger legal value).
 */
class AgentDemoEventHubTest {

    private AgentDemoDtos.DemoEventEnvelope env(long seq, String session, String type) {
        return new AgentDemoDtos.DemoEventEnvelope(seq, "evt-" + seq, session, "run-1",
                type, java.time.Instant.now().toString(), Map.of("seq", seq));
    }

    private AgentDemoEventHub hubWith(List<AgentDemoDtos.DemoEventEnvelope> events) {
        return new AgentDemoEventHub(() -> List.copyOf(events), 16, 64);
    }

    // ------------------------------------------------------------------ //
    // Replay then live
    // ------------------------------------------------------------------ //

    @Test
    void subscribeReplaysDurableEventsAfterThresholdThenStreamsLive() throws Exception {
        List<AgentDemoDtos.DemoEventEnvelope> durable = List.of(
                env(1, "sess-1", "turn.accepted"),
                env(2, "sess-1", "run.started"),
                env(3, "sess-1", "step.completed"));
        AgentDemoEventHub hub = hubWith(durable);

        CollectingEmitter sink = new CollectingEmitter();
        hub.subscribe("sess-1", 1L, null, sink);

        // Replays seq 2 and 3 (after=1), then live.
        List<AgentDemoDtos.DemoEventEnvelope> replayed = sink.drain();
        assertEquals(2, replayed.size());
        assertEquals(2L, replayed.get(0).seq());
        assertEquals(3L, replayed.get(1).seq());

        // A live event arrives after subscription.
        hub.publish(env(4, "sess-1", "run.completed"));
        await(() -> sink.totalReceived() >= 3);
        List<AgentDemoDtos.DemoEventEnvelope> live = sink.drain();
        assertEquals(1, live.size());
        assertEquals(4L, live.get(0).seq());
    }

    @Test
    void noDuplicateSeqAcrossReplayAndLive() throws Exception {
        List<AgentDemoDtos.DemoEventEnvelope> durable = List.of(
                env(1, "sess-1", "turn.accepted"),
                env(2, "sess-1", "run.completed"));
        AgentDemoEventHub hub = hubWith(durable);

        CollectingEmitter sink = new CollectingEmitter();
        hub.subscribe("sess-1", 0L, null, sink);
        // Publish a duplicate of seq 2 (e.g. re-delivery) — the Hub must drop it.
        hub.publish(env(2, "sess-1", "run.completed"));
        hub.publish(env(3, "sess-1", "seed.detected"));

        // The live drain loop is async; wait for seq 3 to arrive.
        await(() -> sink.totalReceived() >= 3);
        List<AgentDemoDtos.DemoEventEnvelope> all = sink.drain();
        // seq 1, 2, 3 — the duplicate seq 2 is dropped.
        assertEquals(3, all.size());
        assertEquals(1L, all.get(0).seq());
        assertEquals(2L, all.get(1).seq());
        assertEquals(3L, all.get(2).seq());
    }

    // ------------------------------------------------------------------ //
    // Reconnection: Last-Event-ID vs query after
    // ------------------------------------------------------------------ //

    @Test
    void lastEventIdTakesPrecedenceWhenLarger() throws Exception {
        List<AgentDemoDtos.DemoEventEnvelope> durable = List.of(
                env(1, "sess-1", "turn.accepted"),
                env(2, "sess-1", "run.started"),
                env(3, "sess-1", "step.completed"),
                env(4, "sess-1", "run.completed"));
        AgentDemoEventHub hub = hubWith(durable);

        CollectingEmitter sink = new CollectingEmitter();
        // query after=1, but Last-Event-ID=3 → resume from 3.
        hub.subscribe("sess-1", 1L, 3L, sink);
        List<AgentDemoDtos.DemoEventEnvelope> replayed = sink.drain();
        assertEquals(1, replayed.size());
        assertEquals(4L, replayed.get(0).seq());
    }

    @Test
    void queryAfterTakesPrecedenceWhenLarger() throws Exception {
        List<AgentDemoDtos.DemoEventEnvelope> durable = List.of(
                env(1, "sess-1", "turn.accepted"),
                env(5, "sess-1", "run.completed"));
        AgentDemoEventHub hub = hubWith(durable);

        CollectingEmitter sink = new CollectingEmitter();
        // query after=4, Last-Event-ID=1 → resume from 4.
        hub.subscribe("sess-1", 4L, 1L, sink);
        List<AgentDemoDtos.DemoEventEnvelope> replayed = sink.drain();
        assertEquals(1, replayed.size());
        assertEquals(5L, replayed.get(0).seq());
    }

    @Test
    void negativeAfterIsRejectedWithStableError() {
        AgentDemoEventHub hub = hubWith(List.of());
        CollectingEmitter sink = new CollectingEmitter();
        SseEmitter result = hub.subscribe("sess-1", -1L, null, sink);
        // The Hub signals an invalid cursor by completing the emitter with an
        // error rather than streaming anything.
        assertNotNull(result);
        assertTrue(sink.closedWithError(), "negative after should error-close");
    }

    @Test
    void onlyEventsForTheRequestedSessionAreReplayed() throws Exception {
        List<AgentDemoDtos.DemoEventEnvelope> durable = List.of(
                env(1, "sess-1", "turn.accepted"),
                env(2, "sess-2", "turn.accepted"),
                env(3, "sess-1", "run.completed"));
        AgentDemoEventHub hub = hubWith(durable);

        CollectingEmitter sink = new CollectingEmitter();
        hub.subscribe("sess-1", 0L, null, sink);
        List<AgentDemoDtos.DemoEventEnvelope> replayed = sink.drain();
        assertEquals(2, replayed.size());
        assertEquals("sess-1", replayed.get(0).sessionId());
        assertEquals("sess-1", replayed.get(1).sessionId());
    }

    // ------------------------------------------------------------------ //
    // Disconnect cleanup + slow consumer
    // ------------------------------------------------------------------ //

    @Test
    void disconnectRemovesEmitterWithoutAffectingOthers() throws Exception {
        AgentDemoEventHub hub = hubWith(List.of());
        CollectingEmitter a = new CollectingEmitter();
        CollectingEmitter b = new CollectingEmitter();
        hub.subscribe("sess-1", 0L, null, a);
        hub.subscribe("sess-1", 0L, null, b);

        a.close(); // simulate client disconnect (sink reports stopped)
        hub.publish(env(10, "sess-1", "run.completed"));

        await(() -> b.totalReceived() >= 1);
        assertTrue(a.drain().isEmpty(), "disconnected emitter must receive nothing");
        assertEquals(1, b.drain().size(), "other emitter still receives live events");
    }

    @Test
    void slowConsumerIsDisconnectedWithoutBlockingTheRun() throws Exception {
        AgentDemoEventHub hub = new AgentDemoEventHub(() -> List.of(), 1, 4);
        BlockingEmitter slow = new BlockingEmitter();
        hub.subscribe("sess-1", 0L, null, slow);

        // Publish many events rapidly; the slow consumer cannot keep up. The
        // Hub must disconnect it and the publish calls must RETURN (not block).
        for (int i = 1; i <= 20; i++) {
            hub.publish(env(i, "sess-1", "assistant.delta"));
        }
        // If we reach here the Run was never blocked. Wait for the disconnect.
        await(slow::wasCompleted);
        assertTrue(slow.wasCompleted(), "slow consumer should have been completed/disconnected");
    }

    @Test
    void heartbeatDoesNotEnterTheDurableStore() throws Exception {
        AtomicInteger storeCalls = new AtomicInteger();
        AgentDemoEventHub hub = new AgentDemoEventHub(
                () -> { storeCalls.incrementAndGet(); return List.of(); },
                16, 64);
        CollectingEmitter sink = new CollectingEmitter();
        hub.subscribe("sess-1", 0L, null, sink);
        // Heartbeats are emitted by the Hub itself; they must NOT call the
        // store supplier or persist anything.
        int before = storeCalls.get();
        hub.sendHeartbeat("sess-1");
        assertEquals(before, storeCalls.get(),
                "heartbeat must not query the durable store");
    }

    @Test
    void publishingToSessionWithNoSubscribersIsSafe() {
        AgentDemoEventHub hub = hubWith(List.of());
        // No subscribers for sess-x; publish must not throw.
        assertDoesNotThrow(() -> hub.publish(env(1, "sess-x", "run.completed")));
    }

    @Test
    void shutdownCompletesAllEmitters() throws Exception {
        AgentDemoEventHub hub = hubWith(List.of());
        CollectingEmitter sink = new CollectingEmitter();
        hub.subscribe("sess-1", 0L, null, sink);
        hub.shutdown();
        assertTrue(sink.closed(), "emitter should be completed on shutdown");
    }

    // ------------------------------------------------------------------ //
    // Test doubles
    // ------------------------------------------------------------------ //

    /** A collecting test sink that records every event it would send. */
    static final class CollectingEmitter implements AgentDemoEventHub.SseSink {
        private final java.util.List<AgentDemoDtos.DemoEventEnvelope> received =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean closed;
        private volatile boolean closedWithError;

        @Override
        public void send(AgentDemoDtos.DemoEventEnvelope env) {
            received.add(env);
        }

        @Override
        public void complete() { closed = true; }

        @Override
        public void completeWithError(Throwable t) {
            closed = true;
            closedWithError = true;
        }

        @Override
        public boolean isStopped() { return closed; }

        List<AgentDemoDtos.DemoEventEnvelope> drain() {
            List<AgentDemoDtos.DemoEventEnvelope> copy = new java.util.ArrayList<>(received);
            received.clear();
            return copy;
        }

        int totalReceived() { return received.size(); }

        boolean closed() { return closed; }
        boolean closedWithError() { return closedWithError; }
        void close() { closed = true; }
    }

    /** A sink that blocks on send (simulates a stuck/slow client). */
    static final class BlockingEmitter implements AgentDemoEventHub.SseSink {
        private volatile boolean completed;
        private final AtomicBoolean block = new AtomicBoolean(true);
        @Override
        public void send(AgentDemoDtos.DemoEventEnvelope env) {
            // Block indefinitely while the gate is set, simulating a client
            // that never drains — the per-subscriber queue fills + overflows.
            while (block.get() && !completed) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        @Override
        public void complete() { completed = true; block.set(false); }
        @Override
        public void completeWithError(Throwable t) { completed = true; block.set(false); }
        @Override
        public boolean isStopped() { return completed; }
        boolean wasCompleted() { return completed; }
    }

    /** Poll a condition until it is true or a timeout elapses (async drain). */
    private static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(10);
        }
    }
}
