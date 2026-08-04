import { useCallback, useEffect, useRef, useState } from 'react';
import { AgentDemoApi, AgentDemoApiError } from './api';
import { applyEvent, INITIAL_RUN_STATE } from './runReducer';
import type {
  DemoSessionDetail,
  DemoSessionSummary,
  RunState,
} from './types';
import { isTerminal } from './types';
import { ConversationPane } from './ConversationPane';
import { HarnessInspector } from './HarnessInspector';

const POLL_MS = 600;

export default function AgentDemoPage() {
  const [sessions, setSessions] = useState<DemoSessionSummary[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [detail, setDetail] = useState<DemoSessionDetail | null>(null);
  const [run, setRun] = useState<RunState>(INITIAL_RUN_STATE);
  const [input, setInput] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [lastFailedTurn, setLastFailedTurn] = useState<{ turnId: string } | null>(null);
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const lastSeqRef = useRef(0);
  const stopStreamRef = useRef<(() => void) | null>(null);

  // Load session list on mount.
  const refreshSessions = useCallback(async () => {
    try {
      const list = await AgentDemoApi.listSessions();
      setSessions(list);
    } catch (e) {
      // ignore on load
    }
  }, []);

  useEffect(() => {
    void refreshSessions();
  }, [refreshSessions]);

  // P1-5: on mount, restore the active session from the URL hash
  // (#/agent-demo?s=<id>); on activeId change, write it back so a refresh
  // restores the same session.
  useEffect(() => {
    const params = new URLSearchParams(
      window.location.hash.split('?')[1] ?? '',
    );
    const sid = params.get('s');
    if (sid) {
      setActiveId(sid);
    }
  }, []);

  useEffect(() => {
    if (!activeId) return;
    const base = '#/agent-demo';
    const newHash = activeId ? `${base}?s=${encodeURIComponent(activeId)}` : base;
    if (window.location.hash !== newHash) {
      window.history.replaceState(null, '', newHash);
    }
  }, [activeId]);

  // Poll the active session detail. Polling refreshes the persisted
  // conversation (turns / seeds / draft) AND keeps the Harness Inspector
  // aligned with the durable Run view (status / steps / tool & model calls /
  // intent / error). This is authoritative and works even when SSE named-event
  // delivery is unreliable in a given browser; SSE is still used for the live
  // capability timeline + assistant streaming when it arrives. Polling MUST
  // NOT advance the SSE cursor (`lastSeqRef`).
  useEffect(() => {
    if (!activeId) return;
    let stopped = false;
    const poll = async () => {
      try {
        const d = await AgentDemoApi.sessionDetail(activeId);
        if (!stopped) {
          setDetail(d);
          syncRunFromDetail(d);
        }
      } catch {
        // ignore transient
      }
    };
    void poll();
    const h = setInterval(poll, POLL_MS);
    return () => {
      stopped = true;
      clearInterval(h);
    };
  }, [activeId, run.status]);

  // When the active session changes (mount / select / refresh), seed the SSE
  // cursor from the session's durable lastEventSeq ONCE, and restore the most
  // recent run's Inspector state from the Session Detail. This does not skip
  // live SSE events because it runs before the stream is opened.
  useEffect(() => {
    if (!activeId) return;
    let stopped = false;
    void AgentDemoApi.sessionDetail(activeId).then((d) => {
      if (stopped) return;
      setDetail(d);
      lastSeqRef.current = d.lastEventSeq ?? 0;
      restoreLatestRun(d);
    }).catch(() => {});
    return () => {
      stopped = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  // SSE stream: subscribe on activeId change; reconnect handled in api.ts.
  useEffect(() => {
    if (!activeId) return;
    stopStreamRef.current?.();
    const stop = AgentDemoApi.openEventStream(
      activeId,
      lastSeqRef.current,
      (seq, type, payload) => {
        // IMPORTANT: compute the reducer's previous-lastSeq BEFORE mutating the
        // ref, and only advance the ref after the event is successfully applied.
        setRun((prev) => {
          const prevLastSeq = lastSeqRef.current;
          const r = applyEvent(prev, { lastSeq: prevLastSeq }, seq, type, payload);
          if (r.applied) lastSeqRef.current = r.lastSeq;
          return r.state;
        });
      },
      () => isTerminal(run.status) && run.runId !== null,
    );
    stopStreamRef.current = stop;
    return () => {
      stop();
      stopStreamRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  // Stop the SSE stream when the run reaches a terminal state.
  useEffect(() => {
    if (isTerminal(run.status)) {
      stopStreamRef.current?.();
      stopStreamRef.current = null;
      // Final detail refresh to capture the assistant turn + seed/draft.
      if (activeId) {
        void AgentDemoApi.sessionDetail(activeId).then(setDetail).catch(() => {});
      }
    }
  }, [run.status, activeId]);

  const handleNewSession = async () => {
    setBusy(true);
    setError(null);
    try {
      const id = await AgentDemoApi.createSession('新会话');
      await refreshSessions();
      setActiveId(id);
      setRun(INITIAL_RUN_STATE);
      setDetail(null);
      lastSeqRef.current = 0;
    } catch (e) {
      setError(toMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const handleSelect = (id: string) => {
    setActiveId(id);
    setRun(INITIAL_RUN_STATE);
    setDetail(null);
    setError(null);
  };

  /**
   * Restore the most recent run's Inspector state from the Session Detail
   * (used on mount / refresh / session switch). The SSE cursor is seeded from
   * the detail's lastEventSeq, so live events arriving AFTER this point are
   * delivered; past events are reflected via the detail-driven run view.
   */
  const restoreLatestRun = (d: DemoSessionDetail) => {
    const runs = d.runs ?? [];
    if (runs.length === 0) return;
    const latest = runs[runs.length - 1];
    setRun({
      ...INITIAL_RUN_STATE,
      runId: latest.id,
      status: latest.status,
      stepsUsed: latest.stepsUsed,
      maxSteps: latest.maxSteps,
      toolCallsUsed: latest.toolCallsUsed,
      maxToolCalls: latest.maxToolCalls,
      modelCallsUsed: latest.modelCallsUsed,
      intent: latest.intent,
      intentLabels: latest.intentLabels ?? [],
      authorization: latest.authorization,
      calibratedConfidence: latest.calibratedConfidence,
      requiresConfirmation: latest.requiresConfirmation ?? false,
      checkpointReason: latest.checkpointReason,
      modelUnavailable: latest.modelUnavailable,
      error: latest.error,
    });
  };

  /**
   * 每次轮询时，把 Inspector 的 Run 字段与【持久 Run view】对齐（中文要点）。
   * 这是 run 状态/计数/intent 的【权威来源】——即便 SSE 命名事件投递在某浏览器不可靠
   * （本机 IAB 的 EventSource 对命名事件不稳），Inspector 仍能正确反映 QUEUED→COMPLETED/
   * FAILED/STOPPED 与 Step/Tool/模型调用计数。
   *
   * 第三轮 Issue B：`intent` 【直接用持久 run 的值】（不再 `prev.intent ?? match.intent`
   * 短路——旧 bug 导致首轮 SSE 设成 GENERAL_QA 后，再也覆盖不成真实 intent），
   * 并同步 intentLabels / authorization / calibratedConfidence / requiresConfirmation。
   * 只更新与当前 `run.runId` 匹配的 run，避免用旧 run 覆盖新 turn 的乐观状态。
   * Timeline 在没有 SSE 数据时从持久 stepsByRun 重建。
   */
  const syncRunFromDetail = (d: DemoSessionDetail) => {
    setRun((prev) => {
      if (!prev.runId) return prev;
      const match = (d.runs ?? []).find((r) => r.id === prev.runId);
      if (!match) return prev;
      // Issue #3: rebuild the Capability Timeline from the DURABLE AgentSteps
      // of this run (persisted by the backend), so the timeline is populated
      // even when SSE named-event delivery is unavailable in the current
      // browser. SSE may still enrich it live when events arrive.
      const stepsForRun = (d.stepsByRun ?? []).filter((s) => s.runId === prev.runId);
      const timeline = stepsForRun.map((s) => ({
        seq: s.seq,
        type: s.kind === 'TOOL' ? 'capability.completed' : `step.${s.kind.toLowerCase()}`,
        capability: s.capability,
        at: s.createdAt,
      }));
      // Prefer the SSE-built timeline when it already has entries (richer);
      // otherwise use the durable one.
      const mergedTimeline = prev.timeline.length > 0 ? prev.timeline : timeline;
      return {
        ...prev,
        status: match.status,
        stepsUsed: match.stepsUsed,
        maxSteps: match.maxSteps,
        toolCallsUsed: match.toolCallsUsed,
        maxToolCalls: match.maxToolCalls,
        modelCallsUsed: match.modelCallsUsed,
        // Issue B (round 3): authoritatively sync the Intent region from the
        // durable run view + persisted IntentResult. The durable intent always
        // wins over a stale SSE-derived GENERAL_QA so the Inspector reflects
        // the real reconciled intent (STRATEGY_CANDIDATE / REFINEMENT /
        // EXECUTION_APPLICATION …).
        intent: match.intent,
        intentLabels: match.intentLabels ?? prev.intentLabels,
        authorization: match.authorization ?? prev.authorization,
        calibratedConfidence: match.calibratedConfidence ?? prev.calibratedConfidence,
        requiresConfirmation: match.requiresConfirmation ?? prev.requiresConfirmation,
        checkpointReason: match.checkpointReason,
        modelUnavailable: match.modelUnavailable,
        error: match.error,
        timeline: mergedTimeline,
      };
    });
  };

  const handleSend = async () => {
    if (!activeId || !input.trim()) return;
    const content = input.trim();
    setInput('');
    setError(null);
    setBusy(true);
    // P0-1（中文要点）：先乐观显示 QUEUED；真实 runId 从 202 响应里取。
    setRun({ ...INITIAL_RUN_STATE, runId: null, status: 'QUEUED' });
    try {
      const accepted = await AgentDemoApi.postTurn(activeId, content);
      // 【关键】保存服务端返回的 accepted.runId，让 Stop 按钮和 Inspector 操作的是【真实 run】，
      // 而不是 null 占位（旧 bug：丢弃返回值 → Stop 按钮 `if(!run.runId) return` 永远点不动）。
      setRun((prev) => ({ ...prev, runId: accepted.runId, status: 'QUEUED' }));
      // The SSE stream + polling will advance the run state and detail.
    } catch (e) {
      const msg = toMessage(e);
      setError(msg);
      setRun(INITIAL_RUN_STATE);
      setBusy(false);
    }
  };

  const handleStop = async () => {
    if (!run.runId) return;
    try {
      await AgentDemoApi.stop(run.runId);
    } catch (e) {
      setError(toMessage(e));
    }
  };

  const handleRetry = async () => {
    if (!lastFailedTurn || !activeId) return;
    setError(null);
    setBusy(true);
    try {
      const accepted = await AgentDemoApi.retry(lastFailedTurn.turnId);
      setRun({ ...INITIAL_RUN_STATE, runId: accepted.runId, status: 'QUEUED' });
      setLastFailedTurn(null);
    } catch (e) {
      setError(toMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const handleCoCreate = async () => {
    const seed = latestSeed();
    if (!seed) return;
    setBusy(true);
    setError(null);
    try {
      await AgentDemoApi.decideSeed(seed.id, 'CO_CREATE');
      if (activeId) setDetail(await AgentDemoApi.sessionDetail(activeId));
    } catch (e) {
      setError(toMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const handleDiscuss = async () => {
    const seed = latestSeed();
    if (!seed) return;
    setBusy(true);
    setError(null);
    try {
      await AgentDemoApi.decideSeed(seed.id, 'DISCUSS');
      if (activeId) setDetail(await AgentDemoApi.sessionDetail(activeId));
    } catch (e) {
      setError(toMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const handlePatchField = async (field: string, value: string) => {
    if (!detail?.activeDraft) return;
    setBusy(true);
    setError(null);
    try {
      await AgentDemoApi.patchDraft(detail.activeDraft.id, detail.activeDraft.version, field, value);
      if (activeId) setDetail(await AgentDemoApi.sessionDetail(activeId));
    } catch (e) {
      setError(toMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const latestSeed = () => {
    if (!detail || detail.seeds.length === 0) return null;
    // The most recent DETECTED seed.
    return detail.seeds[detail.seeds.length - 1];
  };

  // Detect a failed turn to offer retry.
  useEffect(() => {
    if (run.status === 'FAILED' && detail && detail.turns.length > 0) {
      const lastUser = [...detail.turns].reverse().find((t) => t.role === 'USER');
      if (lastUser) setLastFailedTurn({ turnId: lastUser.id });
    }
    if (run.status !== 'FAILED') setBusy(false);
  }, [run.status, detail]);

  const turns = detail?.turns ?? [];
  const seed = latestSeed();
  const draft = detail?.activeDraft ?? null;

  return (
    <div className="agent-demo" data-testid="agent-demo-page">
      <div className="agent-demo-banner" data-testid="demo-banner">
        Demo 不连接 SimNow，不会产生交易行为 · DeepSeek 真实意图 · Mock 证据明确标记
      </div>
      <div className="agent-demo-body">
        <div className="agent-demo-sessions" data-testid="session-list">
          <h3>会话</h3>
          <button
            className="agent-demo-new-session"
            onClick={handleNewSession}
            disabled={busy}
            data-testid="new-session-button"
          >
            + 新建会话
          </button>
          {sessions.map((s) => {
            const isActive = s.id === activeId;
            return (
              <button
                type="button"
                key={s.id}
                className={`agent-demo-session-item ${isActive ? 'active' : ''}`}
                onClick={() => handleSelect(s.id)}
                aria-current={isActive ? 'true' : undefined}
                data-testid={`session-item-${s.id}`}
              >
                <span className="agent-demo-session-title">{s.title}</span>
                <span className="agent-demo-session-time">
                  {formatTime(s.createdAt)}
                </span>
              </button>
            );
          })}
        </div>

        <ConversationPane
          turns={turns}
          seed={seed}
          draft={draft}
          runStatus={run.status}
          input={input}
          error={error}
          busy={busy}
          onInputChange={setInput}
          onSend={handleSend}
          onStop={handleStop}
          onRetry={handleRetry}
          onExample={(t) => setInput(t)}
          onCoCreate={handleCoCreate}
          onDiscuss={handleDiscuss}
          onPatchField={handlePatchField}
          lastFailedTurn={lastFailedTurn}
        />

        <HarnessInspector run={run} />
      </div>
      <button
        className="agent-demo-mobile-inspector-toggle"
        onClick={() => setInspectorOpen((v) => !v)}
        aria-label="切换 Harness 面板"
        aria-expanded={inspectorOpen}
        data-testid="mobile-inspector-toggle"
      >
        ⓘ
      </button>
      {inspectorOpen && (
        // P1-4: the mobile drawer wrapper uses a DISTINCT class
        // (agent-demo-mobile-drawer) so the CSS rule that hides the inner
        // HarnessInspector (.agent-demo-inspector { display:none } on mobile)
        // does not also hide this wrapper — which previously left the drawer
        // visibly empty.
        <div
          className="agent-demo-mobile-drawer open"
          role="dialog"
          aria-modal="false"
          aria-label="Harness 面板"
          data-testid="mobile-inspector-drawer"
        >
          <HarnessInspector run={run} onClose={() => setInspectorOpen(false)} />
        </div>
      )}
    </div>
  );
}

function toMessage(e: unknown): string {
  if (e instanceof AgentDemoApiError) return `${e.code}：${e.message}`;
  if (e instanceof Error) return e.message;
  return '未知错误';
}

/** Short HH:MM (local) rendering of an ISO timestamp for the session list. */
function formatTime(iso: string | undefined): string {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  } catch {
    return '';
  }
}
