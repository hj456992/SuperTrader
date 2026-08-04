import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentDemoApi, AgentDemoApiError } from '../api';

function mockResponse(body: unknown, init: { status?: number; headers?: Record<string, string> } = {}) {
  const status = init.status ?? 200;
  const headers = new Map(Object.entries(init.headers ?? {}));
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers.get(name) ?? null },
    json: async () => body,
  } as unknown as Response;
}

describe('AgentDemoApi', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn();
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('createSession returns the session id from the Location header', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse(null, {
        status: 201,
        headers: { Location: '/api/v1/agent-demo/sessions/sess-abc' },
      }),
    );
    const id = await AgentDemoApi.createSession('新会话');
    expect(id).toBe('sess-abc');
  });

  it('postTurn sends an Idempotency-Key header and body', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse({
        schema: 'agent-demo.accepted.v1',
        sessionId: 'sess-1',
        turnId: 'turn-1',
        runId: 'run-1',
        status: 'QUEUED',
        eventSeq: 1,
      }),
    );
    const res = await AgentDemoApi.postTurn('sess-1', '你好', 'my-key');
    expect(res.runId).toBe('run-1');
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(call[1].headers['Idempotency-Key']).toBe('my-key');
    expect(call[1].body).toBe(JSON.stringify({ content: '你好' }));
  });

  it('postTurn generates an Idempotency-Key when none is provided', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse({
        schema: 'agent-demo.accepted.v1',
        sessionId: 'sess-1',
        turnId: 't',
        runId: 'r',
        status: 'QUEUED',
        eventSeq: 1,
      }),
    );
    await AgentDemoApi.postTurn('sess-1', '你好');
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const key = call[1].headers['Idempotency-Key'] as string;
    expect(key).toMatch(/^key-/);
  });

  it('throws AgentDemoApiError with the stable code on a 409 conflict', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse({ error: { code: 'IDEMPOTENCY_CONFLICT', message: '冲突' } }, { status: 409 }),
    );
    await expect(AgentDemoApi.postTurn('sess-1', 'x', 'k')).rejects.toMatchObject({
      code: 'IDEMPOTENCY_CONFLICT',
      status: 409,
    });
  });

  it('throws SENSITIVE_CONTENT_REJECTED on a 400', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse(
        { error: { code: 'SENSITIVE_CONTENT_REJECTED', message: '敏感内容' } },
        { status: 400 },
      ),
    );
    await expect(AgentDemoApi.postTurn('sess-1', 'password=x', 'k')).rejects.toBeInstanceOf(
      AgentDemoApiError,
    );
  });

  it('patchDraft sends If-Match with the version', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse({
        id: 'draft-1',
        status: 'CO_CREATING',
        version: 2,
        completeness: 10,
        missingFields: [],
        fields: { name: 'x' },
        evidence: [],
        nextQuestion: null,
        frozen: false,
      }),
    );
    await AgentDemoApi.patchDraft('draft-1', 1, 'name', '黄金策略');
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(call[1].headers['If-Match']).toBe('1');
    expect(JSON.parse(call[1].body)).toEqual({ field: 'name', value: '黄金策略' });
  });

  it('decideSeed posts CO_CREATE decision', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse({ draft: { id: 'draft-1', status: 'CO_CREATING' } }),
    );
    await AgentDemoApi.decideSeed('seed-1', 'CO_CREATE');
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(JSON.parse(call[1].body)).toEqual({ decision: 'CO_CREATE' });
  });

  it('retry generates a new idempotency key per call', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue(
      mockResponse({
        schema: 'agent-demo.accepted.v1',
        sessionId: null,
        turnId: 'turn-1',
        runId: 'run-2',
        status: 'QUEUED',
        eventSeq: 5,
      }),
    );
    await AgentDemoApi.retry('turn-1');
    await AgentDemoApi.retry('turn-1');
    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls;
    const k1 = calls[0][1].headers['Idempotency-Key'];
    const k2 = calls[1][1].headers['Idempotency-Key'];
    expect(k1).not.toBe(k2); // Retry always uses a fresh key
  });
});
