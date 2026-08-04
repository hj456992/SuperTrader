import type { RunState } from './types';
import { isTerminal } from './types';

interface Props {
  run: RunState;
  onClose?: () => void;
}

/**
 * The Harness Inspector (right pane / mobile bottom drawer). Shows the
 * structured intent, the run trajectory (status / steps / tool & model calls /
 * elapsed / checkpoint) and the capability timeline. NEVER shows hidden
 * chain-of-thought, raw prompts or credentials.
 */
export function HarnessInspector({ run, onClose }: Props) {
  return (
    <aside className="agent-demo-inspector" data-testid="harness-inspector">
      {onClose && (
        <button
          onClick={onClose}
          aria-label="关闭 Harness 面板"
          style={{ float: 'right', border: 'none', background: 'none', cursor: 'pointer', fontSize: 18 }}
          data-testid="inspector-close"
        >
          ×
        </button>
      )}

      <section data-testid="inspector-intent">
        <h4>当前理解（Intent）</h4>
        <div className="kv">
          <span className="k">主意图</span>
          <span>{run.intent ?? '—'}</span>
        </div>
        <div className="kv">
          <span className="k">标签</span>
          <span>{run.intentLabels.join(', ') || '—'}</span>
        </div>
        <div className="kv">
          <span className="k">授权</span>
          <span>{run.authorization ?? '—'}</span>
        </div>
        <div className="kv">
          <span className="k">校准置信度</span>
          <span>{run.calibratedConfidence == null ? '—' : run.calibratedConfidence.toFixed(2)}</span>
        </div>
        <div className="kv">
          <span className="k">需确认</span>
          <span>{run.requiresConfirmation ? '是' : '否'}</span>
        </div>
        {run.ambiguities.length > 0 && (
          <div data-testid="inspector-ambiguities" style={{ marginTop: 4, color: '#92400e' }}>
            {run.ambiguities.map((a, i) => (
              <div key={i}>· {a}</div>
            ))}
          </div>
        )}
      </section>

      <section data-testid="inspector-trajectory">
        <h4>运行轨迹</h4>
        <div className="kv">
          <span className="k">状态</span>
          <span className={`agent-demo-status-badge status-${run.status}`} data-testid="run-status-badge">
            {run.status}
          </span>
        </div>
        <div className="kv">
          <span className="k">Step</span>
          <span>
            {run.stepsUsed} / {run.maxSteps}
          </span>
        </div>
        <div className="kv">
          <span className="k">Tool 调用</span>
          <span>
            {run.toolCallsUsed} / {run.maxToolCalls}
          </span>
        </div>
        <div className="kv">
          <span className="k">模型调用</span>
          <span>{run.modelCallsUsed} / 4</span>
        </div>
        {run.modelUnavailable && (
          <div style={{ color: '#b45309', marginTop: 4 }} data-testid="model-unavailable-tag">
            MODEL_UNAVAILABLE（未配置或失败）
          </div>
        )}
        {run.checkpointReason && (
          <div className="kv">
            <span className="k">Checkpoint</span>
            <span>{run.checkpointReason}</span>
          </div>
        )}
        {run.error && <div className="agent-demo-error" data-testid="run-error">{run.error}</div>}
      </section>

      <section data-testid="inspector-timeline">
        <h4>Capability Timeline</h4>
        {run.timeline.length === 0 ? (
          <div className="muted" style={{ color: '#94a3b8' }}>暂无步骤</div>
        ) : (
          <ul className="agent-demo-timeline">
            {run.timeline.map((t, i) => (
              <li key={i} data-testid={`timeline-${i}`}>
                <strong>{t.type}</strong>
                {t.capability ? ` · ${t.capability}` : ''}
              </li>
            ))}
          </ul>
        )}
      </section>

      {isTerminal(run.status) && (
        <div style={{ fontSize: 11, color: '#64748b', marginTop: 8 }} data-testid="terminal-note">
          已进入终态 {run.status}，不再产生新 Step。
        </div>
      )}
    </aside>
  );
}
