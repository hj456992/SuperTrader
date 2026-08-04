// Agent Demo API client. Every write request carries an Idempotency-Key
// (generated here unless provided); Draft patches send If-Match; Retry uses a
// new key. No message body, key or prompt is ever persisted to localStorage.

import type {
  AcceptedTurnResponse,
  ApiError,
  DemoDraftView,
  DemoSessionDetail,
  DemoSessionSummary,
} from './types';

const BASE = '/api/v1/agent-demo';

/**
 * P0-1（中文要点）：后端可能发送的【命名事件类型集合】。
 * 后端 AgentDemoEventHub.SseEmitterSink 用 `SseEmitter.event().name(type)` 发送【命名事件】。
 * 关键陷阱：按 EventSource 规范，命名事件【不会触发 onmessage】——只有【没有 event: 字段】的
 * 默认消息才触发 onmessage。所以前端必须为每个已知命名类型 `addEventListener(type, ...)`，
 * 否则【一个事件都收不到】（旧 bug：只用 onmessage，Inspector 永远停在 QUEUED/Step 0）。
 * 同时保留 onmessage 作为未命名事件的兜底。未知类型由对应 addEventListener 命中后，
 * reducer 仍推进 seq 但忽略 payload。
 */
export const SSE_EVENT_TYPES = [
  'turn.accepted',
  'run.started',
  'run.completed',
  'run.failed',
  'run.stopped',
  'run.checkpointed',
  'intent.detected',
  'step.started',
  'step.completed',
  'budget.updated',
  'assistant.delta',
  'citation.added',
  'seed.detected',
  'draft.updated',
  'validation.updated',
  'checkpoint.saved',
  'capability.started',
  'capability.completed',
  'capability.failed',
  'turn.completed',
  'turn.stopped',
  'heartbeat',
] as const;

export class AgentDemoApiError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(code: string, message: string, status: number) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `key-${crypto.randomUUID()}`;
  }
  return `key-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

async function parseError(res: Response): Promise<AgentDemoApiError> {
  let code = 'PERSISTENCE_UNAVAILABLE';
  let message = '请求失败，请重试。';
  try {
    const body = (await res.json()) as { error?: ApiError };
    if (body?.error?.code) code = body.error.code;
    if (body?.error?.message) message = body.error.message;
  } catch {
    // keep defaults
  }
  return new AgentDemoApiError(code, message, res.status);
}

async function jsonOrThrow<T>(res: Response): Promise<T> {
  if (!res.ok) throw await parseError(res);
  return (await res.json()) as T;
}

export const AgentDemoApi = {
  async createSession(title?: string): Promise<string> {
    const res = await fetch(`${BASE}/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: title ?? null }),
    });
    if (!res.ok) throw await parseError(res);
    const loc = res.headers.get('Location') ?? '';
    return loc.substring(loc.lastIndexOf('/') + 1);
  },

  async listSessions(): Promise<DemoSessionSummary[]> {
    return jsonOrThrow(await fetch(`${BASE}/sessions`));
  },

  async sessionDetail(sessionId: string): Promise<DemoSessionDetail> {
    return jsonOrThrow(await fetch(`${BASE}/sessions/${sessionId}`));
  },

  async postTurn(
    sessionId: string,
    content: string,
    idempotencyKey?: string,
  ): Promise<AcceptedTurnResponse> {
    const key = idempotencyKey ?? newIdempotencyKey();
    const res = await fetch(`${BASE}/sessions/${sessionId}/turns`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
      },
      body: JSON.stringify({ content }),
    });
    return jsonOrThrow(res);
  },

  async stop(runId: string, idempotencyKey?: string): Promise<void> {
    const key = idempotencyKey ?? newIdempotencyKey();
    const res = await fetch(`${BASE}/runs/${runId}/stop`, {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
    });
    if (!res.ok) throw await parseError(res);
  },

  async retry(turnId: string, idempotencyKey?: string): Promise<AcceptedTurnResponse> {
    const key = idempotencyKey ?? newIdempotencyKey();
    return jsonOrThrow(
      await fetch(`${BASE}/turns/${turnId}/retry`, {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
      }),
    );
  },

  async decideSeed(
    seedId: string,
    decision: 'CO_CREATE' | 'DISCUSS',
  ): Promise<{ draft: DemoDraftView | null }> {
    return jsonOrThrow(
      await fetch(`${BASE}/seeds/${seedId}/decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ decision }),
      }),
    );
  },

  async patchDraft(
    draftId: string,
    version: number,
    field: string,
    value: string,
  ): Promise<DemoDraftView> {
    return jsonOrThrow(
      await fetch(`${BASE}/drafts/${draftId}`, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          'If-Match': String(version),
        },
        body: JSON.stringify({ field, value }),
      }),
    );
  },

  async validateDraft(draftId: string): Promise<unknown> {
    return jsonOrThrow(
      await fetch(`${BASE}/drafts/${draftId}/validate`, { method: 'POST' }),
    );
  },

  /**
   * Open an SSE event stream. Replays events after `lastSeq`, then streams
   * live events. On disconnect, retries with exponential backoff (1/2/4/8s)
   * carrying the latest seq. Stops reconnecting once `shouldStop()` is true.
   * Returns a disposer that closes the EventSource.
   */
  openEventStream(
    sessionId: string,
    lastSeq: number,
    onEvent: (seq: number, type: string, payload: Record<string, unknown>) => void,
    shouldStop: () => boolean,
  ): () => void {
    let closed = false;
    let attempt = 0;
    let es: EventSource | null = null;
    let currentSeq = lastSeq;

    const open = () => {
      if (closed || shouldStop()) return;
      es = new EventSource(`${BASE}/sessions/${sessionId}/events?after=${currentSeq}`);
      // Named events: the backend emits `event:<type>` lines. Register a
      // listener for every known named type so they are actually delivered
      // (onmessage only fires for events WITHOUT an `event:` field).
      const dispatch = (ev: MessageEvent) => handle(ev);
      es.onmessage = dispatch; // default/unnamed events
      for (const type of SSE_EVENT_TYPES) {
        es.addEventListener(type, dispatch as EventListener);
      }
      es.addEventListener('open', () => {
        attempt = 0;
      });
      es.addEventListener('error', () => {
        es?.close();
        es = null;
        if (closed || shouldStop()) return;
        attempt += 1;
        const backoff = Math.min(8000, 1000 * 2 ** Math.min(attempt - 1, 3));
        setTimeout(open, backoff);
      });
    };

    const handle = (ev: MessageEvent) => {
      try {
        const env = JSON.parse(ev.data);
        const seq = typeof env.seq === 'number' ? env.seq : -1;
        if (seq < 0) return;
        if (seq <= currentSeq) return; // dedup
        currentSeq = seq;
        onEvent(seq, env.type ?? 'event', env.payload ?? {});
      } catch {
        // ignore malformed
      }
    };

    open();
    return () => {
      closed = true;
      es?.close();
      es = null;
    };
  },
};
