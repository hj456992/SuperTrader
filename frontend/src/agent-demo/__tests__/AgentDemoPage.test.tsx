import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import AgentDemoPage from '../AgentDemoPage';

/**
 * A minimal in-memory backend double for the Agent Demo REST + SSE contract.
 * Captures posted turns, seed decisions and draft patches so the page test can
 * assert the full chat → Seed → CO_CREATE → Draft → Mock-evidence flow without
 * a real backend (and without ever calling a model).
 */
class FakeBackend {
  sessions: Array<{ id: string; title: string; turns: any[]; seeds: any[]; draft: any }> = [];
  eventSeq = 0;
  postedTurns: Array<{ sessionId: string; content: string }> = [];
  seedDecisions: Array<{ seedId: string; decision: string }> = [];
  draftPatches: Array<{ draftId: string; field: string; value: string }> = [];

  list() {
    return this.sessions.map((s) => ({
      id: s.id,
      title: s.title,
      status: 'ACTIVE',
      createdAt: '2026-08-03T00:00:00Z',
      lastEventSeq: this.eventSeq,
    }));
  }

  detail(id: string) {
    const s = this.sessions.find((x) => x.id === id);
    if (!s) throw { status: 404 };
    return {
      id: s.id,
      workspaceId: 'ws-demo',
      title: s.title,
      status: 'ACTIVE',
      turns: s.turns,
      runs: [],
      stepsByRun: [],
      seeds: s.seeds,
      activeDraft: s.draft,
      lastEventSeq: this.eventSeq,
    };
  }
}

let backend = new FakeBackend();

function mockFetch() {
  return vi.fn(async (url: string, init?: any) => {
    const u = String(url);
    if (u.endsWith('/sessions') && init?.method === 'POST') {
      const id = `sess-${backend.sessions.length + 1}`;
      backend.sessions.push({ id, title: '新会话', turns: [], seeds: [], draft: null });
      return {
        ok: true,
        status: 201,
        headers: { get: (n: string) => (n === 'Location' ? `/api/v1/agent-demo/sessions/${id}` : null) },
        json: async () => null,
      } as any;
    }
    if (u.endsWith('/sessions') && !init?.method) {
      return { ok: true, status: 200, json: async () => backend.list() } as any;
    }
    if (u.match(/\/sessions\/[^/]+$/) && !init?.method) {
      const id = u.split('/').pop()!;
      try {
        return { ok: true, status: 200, json: async () => backend.detail(id) } as any;
      } catch {
        return { ok: false, status: 404, json: async () => ({ error: { code: 'NOT_FOUND', message: 'x' } }) } as any;
      }
    }
    if (u.match(/\/sessions\/[^/]+\/turns$/) && init?.method === 'POST') {
      const id = u.split('/')[5];
      const body = JSON.parse(init.body);
      backend.postedTurns.push({ sessionId: id, content: body.content });
      const turnId = `turn-${backend.eventSeq + 1}`;
      const runId = `run-${backend.eventSeq + 1}`;
      backend.eventSeq += 1;
      // Simulate the assistant reply appearing in the session detail.
      const s = backend.sessions.find((x) => x.id === id)!;
      s.turns.push({ id: turnId, role: 'USER', content: body.content, intent: null, modelUnavailable: false, seed: null, createdAt: 't' });
      // For a strategy candidate, add a detected seed.
      if (body.content.includes('黄金')) {
        s.seeds.push({ id: `seed-${backend.eventSeq}`, summary: body.content, status: 'DETECTED', sourceTurnId: turnId });
        s.turns.push({ id: `turn-a${backend.eventSeq}`, role: 'ASSISTANT', content: '检测到策略候选', intent: 'STRATEGY_CANDIDATE', modelUnavailable: true, seed: null, createdAt: 't' });
      } else {
        s.turns.push({ id: `turn-a${backend.eventSeq}`, role: 'ASSISTANT', content: '收到', intent: 'GENERAL_QA', modelUnavailable: true, seed: null, createdAt: 't' });
      }
      return {
        ok: true,
        status: 202,
        json: async () => ({ schema: 'agent-demo.accepted.v1', sessionId: id, turnId, runId, status: 'QUEUED', eventSeq: backend.eventSeq }),
      } as any;
    }
    if (u.match(/\/seeds\/[^/]+\/decision$/) && init?.method === 'POST') {
      const seedId = u.split('/')[5];
      const body = JSON.parse(init.body);
      backend.seedDecisions.push({ seedId, decision: body.decision });
      if (body.decision === 'CO_CREATE') {
        for (const s of backend.sessions) {
          const seed = s.seeds.find((x: any) => x.id === seedId);
          if (seed) seed.status = 'CONFIRMED';
          s.draft = {
            id: `draft-${seedId}`,
            status: 'CO_CREATING',
            version: 0,
            completeness: 0,
            missingFields: ['instruments', 'timeframe'],
            fields: {},
            evidence: [
              { fieldPath: 'instruments', sourceType: 'FIXTURE', sourceId: 'turn-1', productionEvidence: false, excerpt: '黄金' },
            ],
            nextQuestion: '请确认合约代码',
            frozen: false,
          };
        }
      } else {
        for (const s of backend.sessions) {
          const seed = s.seeds.find((x: any) => x.id === seedId);
          if (seed) seed.status = 'DISCUSSED';
        }
      }
      return { ok: true, status: 200, json: async () => ({ draft: null }) } as any;
    }
    if (u.match(/\/drafts\/[^/]+$/) && init?.method === 'PATCH') {
      const draftId = u.split('/').pop()!;
      const body = JSON.parse(init.body);
      backend.draftPatches.push({ draftId, field: body.field, value: body.value });
      for (const s of backend.sessions) {
        if (s.draft && s.draft.id === draftId) {
          s.draft.version += 1;
          s.draft.fields[body.field] = body.value;
        }
      }
      return { ok: true, status: 200, json: async () => backend.sessions.find((x) => x.draft?.id === draftId)!.draft } as any;
    }
    return { ok: false, status: 404, json: async () => ({ error: { code: 'NOT_FOUND', message: 'unhandled ' + u } }) } as any;
  });
}

// Minimal EventSource mock: supports NAMED events via addEventListener (the
// backend sends `event:<type>` lines; onmessage alone would drop them).
class MockEventSource {
  static instances: MockEventSource[] = [];
  onmessage: ((ev: any) => void) | null = null;
  onerror: ((ev: any) => void) | null = null;
  listeners: Record<string, ((ev: any) => void)[]> = {};
  close = vi.fn(() => {});
  addEventListener(type: string, cb: (ev: any) => void) {
    (this.listeners[type] ||= []).push(cb);
  }
  constructor(public url: string) {
    MockEventSource.instances.push(this);
  }
  /** Dispatch a NAMED event the way a real browser EventSource does. */
  emitNamed(type: string, data: unknown) {
    const ev = { data: typeof data === 'string' ? data : JSON.stringify(data) } as any;
    (this.listeners[type] || []).forEach((cb) => cb(ev));
  }
}

describe('AgentDemoPage', () => {
  const originalFetch = globalThis.fetch;
  const originalEventSource = globalThis.EventSource;

  beforeEach(() => {
    backend = new FakeBackend();
    (globalThis as any).fetch = mockFetch();
    (globalThis as any).EventSource = MockEventSource as any;
    MockEventSource.instances = [];
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    (globalThis as any).EventSource = originalEventSource;
  });

  it('renders the demo banner and empty state', async () => {
    render(<AgentDemoPage />);
    expect(screen.getByTestId('demo-banner')).toBeTruthy();
    expect(screen.getByTestId('conversation-pane')).toBeTruthy();
  });

  it('creates a new session and shows it in the list', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
  });

  it('sends a plain QA message and shows the assistant reply with no strategy card', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
    const input = screen.getByTestId('message-input') as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: '你好，简单介绍均线' } });
    fireEvent.click(screen.getByTestId('send-button'));
    await waitFor(() => expect(screen.queryAllByText('你好，简单介绍均线').length).toBeGreaterThan(0));
    // No seed / draft card for plain QA.
    await waitFor(() => expect(screen.queryByTestId('seed-card')).toBeNull());
    await waitFor(() => expect(screen.queryByTestId('draft-card')).toBeNull());
  });

  it('a strategy candidate shows a Seed card with confirm buttons', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
    const input = screen.getByTestId('message-input') as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: '黄金5日线上穿20日线买入' } });
    fireEvent.click(screen.getByTestId('send-button'));
    await waitFor(() => expect(screen.queryByTestId('seed-card')).not.toBeNull());
    expect(screen.getByTestId('seed-co-create')).toBeTruthy();
    expect(screen.getByTestId('seed-discuss')).toBeTruthy();
  });

  it('CO_CREATE creates a Draft with Mock evidence tags', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
    const input = screen.getByTestId('message-input') as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: '黄金5日线上穿20日线买入' } });
    fireEvent.click(screen.getByTestId('send-button'));
    await waitFor(() => expect(screen.queryByTestId('seed-card')).not.toBeNull());
    fireEvent.click(screen.getByTestId('seed-co-create'));
    await waitFor(() => expect(screen.queryByTestId('draft-card')).not.toBeNull());
    // The three 草案 tags.
    expect(screen.getByTestId('draft-tag-draft').textContent).toBe('草案');
    expect(screen.getByTestId('draft-tag-unfrozen').textContent).toBe('未冻结');
    expect(screen.getByTestId('draft-tag-no-exec').textContent).toBe('无执行能力');
    // Mock evidence explicitly labeled.
    expect(screen.queryAllByTestId('mock-evidence-tag').length).toBeGreaterThan(0);
    const tag = screen.getAllByTestId('mock-evidence-tag')[0].textContent!;
    expect(tag).toContain('FIXTURE');
    expect(tag).toContain('productionEvidence=false');
    expect(tag).toContain('Mock 证据');
  });

  it('DISCUSS does not create a Draft', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
    const input = screen.getByTestId('message-input') as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: '黄金5日线上穿20日线买入' } });
    fireEvent.click(screen.getByTestId('send-button'));
    await waitFor(() => expect(screen.queryByTestId('seed-card')).not.toBeNull());
    fireEvent.click(screen.getByTestId('seed-discuss'));
    // Give the refresh a moment; no draft should appear.
    await waitFor(() => expect(backend.seedDecisions.length).toBe(1));
    expect(backend.seedDecisions[0].decision).toBe('DISCUSS');
    expect(screen.queryByTestId('draft-card')).toBeNull();
  });

  it('shows the Harness Inspector with status badge', async () => {
    render(<AgentDemoPage />);
    expect(screen.getByTestId('harness-inspector')).toBeTruthy();
    expect(screen.getByTestId('run-status-badge').textContent).toBe('IDLE');
  });

  it('P0-1: NAMED SSE events drive the Harness Inspector (intent, steps, status)', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
    const input = screen.getByTestId('message-input') as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: '你好' } });
    fireEvent.click(screen.getByTestId('send-button'));

    // The 202 response carries a runId that the page MUST persist.
    await waitFor(() => expect(screen.getByTestId('run-status-badge').textContent).toBe('QUEUED'));

    // The backend sends NAMED events. A real browser fires addEventListener
    // (NOT onmessage) for these. Simulate the full event sequence.
    const es = MockEventSource.instances[0]!;
    const env = (seq: number, type: string, payload: any) => ({
      schema: 'agent-demo.event.v1',
      seq,
      eventId: `e${seq}`,
      sessionId: 'sess-1',
      runId: 'run-1',
      type,
      at: 't',
      payload,
    });
    await act(async () => {
      es.emitNamed('turn.accepted', env(1, 'turn.accepted', { runId: 'run-1', status: 'QUEUED' }));
    });
    await act(async () => {
      es.emitNamed('run.started', env(2, 'run.started', { status: 'RUNNING' }));
    });
    await waitFor(() => expect(screen.getByTestId('run-status-badge').textContent).toBe('RUNNING'));
    await act(async () => {
      es.emitNamed('intent.detected', env(3, 'intent.detected', {
        primaryIntent: 'STRATEGY_CANDIDATE',
        labels: ['STRATEGY_CANDIDATE'],
        calibratedConfidence: 0.82,
        requiresConfirmation: true,
      }));
    });
    // Intent now visible in the Inspector (P0-1 success criterion).
    await waitFor(() =>
      expect(screen.getByTestId('inspector-intent').textContent).toContain('STRATEGY_CANDIDATE'),
    );
    await act(async () => {
      es.emitNamed('step.started', env(4, 'step.started', { capability: 'model.intent_classify' }));
      es.emitNamed('budget.updated', env(5, 'budget.updated', { stepsUsed: 2, toolCallsUsed: 1 }));
      es.emitNamed('run.completed', env(6, 'run.completed', { status: 'COMPLETED' }));
    });
    // Step / tool counters visible; terminal COMPLETED.
    await waitFor(() => expect(screen.getByTestId('run-status-badge').textContent).toBe('COMPLETED'));
    expect(screen.getByTestId('inspector-trajectory').textContent).toContain('2 / 8');
    expect(screen.getByTestId('inspector-trajectory').textContent).toContain('1 / 12');
    // The capability timeline captured the named step event.
    expect(screen.getByTestId('inspector-timeline').textContent).toContain('model.intent_classify');
  });

  it('P0-2 precondition: the Stop button is enabled once a real runId is persisted', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
    const input = screen.getByTestId('message-input') as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: '你好' } });
    fireEvent.click(screen.getByTestId('send-button'));
    // After the 202, the Stop button replaces Send (runId is now non-null).
    await waitFor(() => expect(screen.queryByTestId('stop-button')).not.toBeNull());
    // Clicking Stop issues a real HTTP request (asserted at the API layer).
    expect((globalThis.fetch as any).mock.calls.some((c: any[]) => /\/runs\/[^/]+\/stop$/.test(c[0]))).toBe(false);
    fireEvent.click(screen.getByTestId('stop-button'));
    await waitFor(() =>
      expect((globalThis.fetch as any).mock.calls.some((c: any[]) => /\/runs\/[^/]+\/stop$/.test(c[0]))).toBe(true),
    );
  });

  it('P1-5: session items are buttons with aria-current on the active one', async () => {
    render(<AgentDemoPage />);
    fireEvent.click(screen.getByTestId('new-session-button'));
    await waitFor(() => expect(screen.getAllByTestId(/session-item-/).length).toBe(1));
    const item = screen.getByTestId(/session-item-/) as HTMLButtonElement;
    expect(item.tagName).toBe('BUTTON');
    // The active session is marked aria-current.
    expect(item.getAttribute('aria-current')).toBe('true');
  });

  it('P1-4: the mobile inspector drawer shows visible content when open', async () => {
    render(<AgentDemoPage />);
    // The desktop inspector is always rendered.
    expect(screen.getByTestId('harness-inspector')).toBeTruthy();
    // Open the mobile drawer.
    fireEvent.click(screen.getByTestId('mobile-inspector-toggle'));
    const drawer = await screen.findByTestId('mobile-inspector-drawer');
    expect(drawer).toBeTruthy();
    // The drawer contains a HarnessInspector with visible intent/trajectory
    // sections (NOT display:none via a duplicated class).
    expect(drawer.querySelector('[data-testid="inspector-intent"]')).toBeTruthy();
    expect(drawer.querySelector('[data-testid="inspector-trajectory"]')).toBeTruthy();
    // The drawer has a close button.
    expect(drawer.querySelector('[data-testid="inspector-close"]')).toBeTruthy();
  });
});
