import type { DemoDraftView, DemoEvidenceView, DemoSeedView } from './types';

interface Props {
  seed: DemoSeedView | null;
  draft: DemoDraftView | null;
  onCoCreate: () => void;
  onDiscuss: () => void;
  onPatchField: (field: string, value: string) => void;
  busy: boolean;
}

/**
 * The strategy artifact card. Shows a detected Seed with confirm buttons
 * (CO_CREATE creates a Draft; DISCUSS keeps it as discussion). Once a Draft
 * exists it shows the fields, Mock evidence, completeness and the next
 * single highest-impact question. Always labeled 草案 / 未冻结 / 无执行能力.
 */
export function StrategyArtifactCard({
  seed,
  draft,
  onCoCreate,
  onDiscuss,
  onPatchField,
  busy,
}: Props) {
  if (!seed && !draft) return null;

  return (
    <div data-testid="strategy-artifact-card">
      {seed && seed.status === 'DETECTED' && (
        <div className="agent-demo-seed-card" data-testid="seed-card">
          <strong>检测到策略候选（StrategySeed）</strong>
          <div className="muted" style={{ fontSize: 12, color: '#64748b' }}>
            {seed.summary}
          </div>
          <div className="seed-actions">
            <button
              className="co-create"
              onClick={onCoCreate}
              disabled={busy}
              data-testid="seed-co-create"
              aria-label="继续完善策略，进入策略共创"
            >
              继续完善策略
            </button>
            <button
              className="discuss"
              onClick={onDiscuss}
              disabled={busy}
              data-testid="seed-discuss"
              aria-label="仅作为讨论，不创建草案"
            >
              仅作为讨论
            </button>
          </div>
        </div>
      )}

      {draft && (
        <div className="agent-demo-draft-card" data-testid="draft-card">
          <strong>策略草案</strong>
          <span className="draft-tag" data-testid="draft-tag-draft">草案</span>
          <span className="draft-tag" data-testid="draft-tag-unfrozen">未冻结</span>
          <span className="draft-tag" data-testid="draft-tag-no-exec">无执行能力</span>
          <div className="kv" style={{ marginTop: 6, fontSize: 12 }}>
            <span className="k">完整度</span>
            <span>{draft.completeness}%（v{draft.version}）</span>
          </div>
          <div className="kv" style={{ fontSize: 12 }}>
            <span className="k">待确认</span>
            <span>{draft.missingFields.length > 0 ? draft.missingFields.join('、') : '无'}</span>
          </div>
          {draft.nextQuestion && (
            <div data-testid="draft-next-question" style={{ marginTop: 6, fontSize: 12, color: '#1e40af' }}>
              {draft.nextQuestion}
            </div>
          )}
          <EvidenceList evidence={draft.evidence} />
          <FieldCorrector draft={draft} onPatchField={onPatchField} busy={busy} />
        </div>
      )}
    </div>
  );
}

function EvidenceList({ evidence }: { evidence: DemoEvidenceView[] }) {
  if (!evidence || evidence.length === 0) return null;
  return (
    <div data-testid="draft-evidence" style={{ marginTop: 6 }}>
      <div style={{ fontSize: 11, color: '#64748b', marginBottom: 2 }}>字段证据</div>
      {evidence.map((e, i) => (
        <div className="agent-demo-evidence-row" key={i}>
          <span>{e.fieldPath}</span>
          {e.excerpt && <span>：{e.excerpt}</span>}
          <span className="mock-tag" data-testid="mock-evidence-tag">
            {e.sourceType} · productionEvidence={String(e.productionEvidence)} · Mock 证据
          </span>
        </div>
      ))}
    </div>
  );
}

function FieldCorrector({
  draft,
  onPatchField,
  busy,
}: {
  draft: DemoDraftView;
  onPatchField: (field: string, value: string) => void;
  busy: boolean;
}) {
  const fields = [
    'name',
    'entryCondition',
    'exitCondition',
    'riskLimits',
    'fastWindow',
    'slowWindow',
    'positionSize',
    'stopLossPct',
  ];
  let field = draft.missingFields[0] ?? 'name';
  return (
    <div className="agent-demo-field-correct" data-testid="draft-field-correct">
      <select
        aria-label="选择要纠正的字段"
        defaultValue={field}
        onChange={(e) => (field = e.target.value)}
      >
        {fields.map((f) => (
          <option key={f} value={f}>
            {f}
          </option>
        ))}
      </select>
      <input
        aria-label="字段新值"
        placeholder="输入字段值以纠正"
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            const v = (e.target as HTMLInputElement).value;
            if (v.trim()) onPatchField(field, v.trim());
            (e.target as HTMLInputElement).value = '';
          }
        }}
      />
      <button
        onClick={() => {
          const input = document.querySelector<HTMLInputElement>(
            '[aria-label="字段新值"]',
          );
          if (input && input.value.trim()) {
            onPatchField(field, input.value.trim());
            input.value = '';
          }
        }}
        disabled={busy}
        data-testid="draft-apply-field"
      >
        纠正字段
      </button>
    </div>
  );
}
