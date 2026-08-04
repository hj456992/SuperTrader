package com.supertrader.demo.taskcenter;

import java.util.Map;

/**
 * Publishes safe, structured run events from the unique Agent Runtime Harness
 * to the application layer (Task 4 / design §7.3).
 *
 * <p><b>Security:</b> the observer only ever receives STRUCTURED, sanitised
 * information — stage, intent, capability, input/output summaries, budget
 * usage, duration and error codes. It NEVER receives hidden chain-of-thought,
 * raw prompts, raw model requests/responses, credentials or unmasked user
 * content. Anything that would be unsafe to show in the UI / store is dropped
 * before an event is built.
 *
 * <p>Event types mirror the SSE vocabulary so the application layer can fan
 * them out without re-translation: {@code run.started}, {@code intent.detected},
 * {@code step.started}, {@code step.completed}, {@code budget.updated},
 * {@code assistant.delta}, {@code seed.detected}, {@code draft.updated},
 * {@code checkpoint.saved}, {@code run.completed}, {@code run.failed},
 * {@code run.stopped}.
 */
public interface HarnessObserver {

    /** A single safe structured event. */
    record Event(String type, Map<String, Object> payload) {
        public Event {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /** Called for each event. Implementations must be non-blocking. */
    void onEvent(Event event);

    /** A no-op implementation (for tests and callers that ignore events). */
    NoOpHarnessObserver NO_OP = new NoOpHarnessObserver();

    /** Convenience final class for the no-op singleton. */
    final class NoOpHarnessObserver implements HarnessObserver {
        @Override public void onEvent(Event event) { /* no-op */ }
    }

    /** Helper: emit a typed event with a sanitised payload. */
    static Event event(String type, Map<String, Object> payload) {
        return new Event(type, payload);
    }
}
