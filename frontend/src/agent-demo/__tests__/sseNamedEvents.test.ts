import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentDemoApi } from '../api';

/**
 * P0-1 regression: the backend SSE emitter sends NAMED events
 * (SseEmitter.event().name(type)), e.g. `event:run.started`. The browser
 * EventSource spec ONLY fires `onmessage` for events with NO `event:` field;
 * named events fire `addEventListener(type, …)` instead. These tests prove the
 * API client dispatches named events (and dedups by seq, and still advances
 * seq for unknown types) using a faithful named-event EventSource mock.
 */

/** A faithful EventSource mock that dispatches NAMED events via addEventListener. */
class NamedEventSource {
  static instances: NamedEventSource[] = [];
  url: string;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  onopen: ((ev: Event) => void) | null = null;
  private listeners = new Map<string, Set<(ev: MessageEvent) => void>>();
  readyState = 0;
  close = vi.fn(() => {
    this.readyState = 2;
  });

  constructor(url: string) {
    this.url = url;
    NamedEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: (ev: MessageEvent) => void) {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(cb);
  }

  removeEventListener(type: string, cb: (ev: MessageEvent) => void) {
    this.listeners.get(type)?.delete(cb);
  }

  /** Test helper: dispatch a NAMED event the way a real browser does. */
  emitNamed(type: string, data: unknown, id?: string) {
    const ev = new MessageEvent(type, { data: typeof data === 'string' ? data : JSON.stringify(data) });
    Object.defineProperty(ev, 'lastEventId', { value: id ?? '' });
    const set = this.listeners.get(type);
    if (set) for (const cb of set) cb(ev);
  }

  /** Test helper: dispatch a default (unnamed) message. */
  emitDefault(data: unknown) {
    const ev = new MessageEvent('message', { data: typeof data === 'string' ? data : JSON.stringify(data) });
    this.onmessage?.(ev);
  }

  emitError() {
    const ev = new Event('error');
    this.onerror?.(ev);
    this.listeners.get('error')?.forEach((cb) => cb(ev as unknown as MessageEvent));
  }
}

function envelope(seq: number, type: string, payload: Record<string, unknown> = {}) {
  return {
    schema: 'agent-demo.event.v1',
    seq,
    eventId: `evt-${seq}`,
    sessionId: 'sess-1',
    runId: 'run-1',
    type,
    at: '2026-08-03T00:00:00Z',
    payload,
  };
}

describe('AgentDemoApi.openEventStream — named SSE events (P0-1)', () => {
  const originalEventSource = globalThis.EventSource;

  beforeEach(() => {
    (globalThis as any).EventSource = NamedEventSource as any;
    NamedEventSource.instances = [];
  });
  afterEach(() => {
    (globalThis as any).EventSource = originalEventSource;
  });

  it('receives a NAMED run.started event (not just default onmessage)', () => {
    const received: Array<{ seq: number; type: string }> = [];
    AgentDemoApi.openEventStream(
      'sess-1',
      0,
      (seq, type) => received.push({ seq, type }),
      () => false,
    );
    const es = NamedEventSource.instances[0]!;
    // Backend sends named events. A real onmessage-only handler would drop these.
    es.emitNamed('turn.accepted', envelope(1, 'turn.accepted', { runId: 'run-1' }), '1');
    es.emitNamed('run.started', envelope(2, 'run.started'), '2');
    expect(received).toEqual([
      { seq: 1, type: 'turn.accepted' },
      { seq: 2, type: 'run.started' },
    ]);
  });

  it('dedups by seq and ignores events at or below the cursor', () => {
    const received: number[] = [];
    AgentDemoApi.openEventStream(
      'sess-1',
      0,
      (seq) => received.push(seq),
      () => false,
    );
    const es = NamedEventSource.instances[0]!;
    es.emitNamed('run.started', envelope(5, 'run.started'), '5');
    es.emitNamed('run.started', envelope(5, 'run.started'), '5'); // dup
    es.emitNamed('run.started', envelope(3, 'run.started'), '3'); // below cursor
    expect(received).toEqual([5]);
  });

  it('still advances seq for a known-but-future event type with empty payload', () => {
    const received: Array<{ seq: number; type: string }> = [];
    AgentDemoApi.openEventStream(
      'sess-1',
      0,
      (seq, type) => received.push({ seq, type }),
      () => false,
    );
    const es = NamedEventSource.instances[0]!;
    // A type the client knows by name but whose payload shape may evolve — the
    // reducer must still advance seq (the contract: unknown/extra events are
    // ignored for state but advance the high-water mark).
    es.emitNamed('citation.added', envelope(7, 'citation.added'), '7');
    expect(received).toEqual([{ seq: 7, type: 'citation.added' }]);
  });

  it('subscribes with the after cursor and reconnects with the latest seq', () => {
    const created: string[] = [];
    const instances: NamedEventSource[] = [];
    class ReconnectES extends NamedEventSource {
      constructor(url: string) {
        super(url);
        created.push(url);
        instances.push(this);
      }
    }
    (globalThis as any).EventSource = ReconnectES;
    vi.useFakeTimers();
    try {
      AgentDemoApi.openEventStream(
        'sess-1',
        42,
        () => {},
        () => false,
      );
      expect(created[0]).toContain('after=42');
      // Advance seq via a named event.
      instances[0]!.emitNamed('run.completed', envelope(99, 'run.completed'), '99');
      // Trigger an error -> should schedule reconnect with after=99.
      instances[0]!.emitError();
      vi.advanceTimersByTime(2000);
      expect(created.length).toBe(2);
      expect(created[1]).toContain('after=99');
    } finally {
      vi.useRealTimers();
      (globalThis as any).EventSource = originalEventSource;
    }
  });

  it('stops reconnecting once shouldStop() is true', () => {
    const created: string[] = [];
    const instances: NamedEventSource[] = [];
    class StopES extends NamedEventSource {
      constructor(url: string) {
        super(url);
        created.push(url);
        instances.push(this);
      }
    }
    (globalThis as any).EventSource = StopES;
    vi.useFakeTimers();
    try {
      let stop = false;
      AgentDemoApi.openEventStream(
        'sess-1',
        0,
        () => {},
        () => stop,
      );
      instances[0]!.emitError();
      vi.advanceTimersByTime(2000);
      expect(created.length).toBe(2);
      stop = true;
      instances[1]!.emitError();
      vi.advanceTimersByTime(20000);
      expect(created.length).toBe(2); // no further reconnect
    } finally {
      vi.useRealTimers();
      (globalThis as any).EventSource = originalEventSource;
    }
  });
});
