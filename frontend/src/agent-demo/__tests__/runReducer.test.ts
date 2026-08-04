import { describe, expect, it } from 'vitest';
import { applyEvent, INITIAL_RUN_STATE } from '../runReducer';
import type { ReducerContext } from '../runReducer';

const ctx = (): ReducerContext => ({ lastSeq: 0 });

describe('runReducer', () => {
  it('ignores events at or below lastSeq (dedup)', () => {
    const r1 = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'run.started', {});
    expect(r1.lastSeq).toBe(1);
    const r2 = applyEvent(r1.state, { lastSeq: r1.lastSeq }, 1, 'run.started', {});
    expect(r2.applied).toBe(false);
    expect(r2.lastSeq).toBe(1);
  });

  it('advances seq even for unknown event types', () => {
    const r = applyEvent(INITIAL_RUN_STATE, ctx(), 5, 'some.unknown.event', {});
    expect(r.applied).toBe(true);
    expect(r.lastSeq).toBe(5);
    expect(r.state.status).toBe('IDLE');
  });

  it('appends assistant.delta text', () => {
    let r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'assistant.delta', { text: 'Hello' });
    r = applyEvent(r.state, { lastSeq: r.lastSeq }, 2, 'assistant.delta', { text: ' world' });
    expect(r.state.deltaText).toBe('Hello world');
  });

  it('records intent.detected fields', () => {
    const r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'intent.detected', {
      primaryIntent: 'STRATEGY_CANDIDATE',
      labels: ['STRATEGY_CANDIDATE'],
      authorization: 'NOT_CONFIRMED',
      calibratedConfidence: 0.79,
      requiresConfirmation: true,
      ambiguities: ['模糊'],
      nextQuestion: '请确认标的',
    });
    expect(r.state.intent).toBe('STRATEGY_CANDIDATE');
    expect(r.state.intentLabels).toEqual(['STRATEGY_CANDIDATE']);
    expect(r.state.calibratedConfidence).toBe(0.79);
    expect(r.state.requiresConfirmation).toBe(true);
    expect(r.state.nextQuestion).toBe('请确认标的');
    expect(r.state.ambiguities).toEqual(['模糊']);
  });

  it('updates budget fields', () => {
    const r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'budget.updated', {
      stepsUsed: 3,
      toolCallsUsed: 2,
      maxSteps: 8,
      maxToolCalls: 12,
    });
    expect(r.state.stepsUsed).toBe(3);
    expect(r.state.toolCallsUsed).toBe(2);
    expect(r.state.maxSteps).toBe(8);
  });

  it('reaches COMPLETED terminal state and ignores later non-terminal events', () => {
    let r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'run.started', {});
    r = applyEvent(r.state, { lastSeq: r.lastSeq }, 2, 'run.completed', {});
    expect(r.state.status).toBe('COMPLETED');
    // A late non-terminal event should not change the terminal status.
    r = applyEvent(r.state, { lastSeq: r.lastSeq }, 3, 'assistant.delta', { text: 'late' });
    expect(r.state.status).toBe('COMPLETED');
    expect(r.state.deltaText).toBe(''); // ignored
    expect(r.lastSeq).toBe(3); // but seq advanced
  });

  it('reaches FAILED with error', () => {
    const r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'run.failed', { reason: 'MODEL_TIMEOUT' });
    expect(r.state.status).toBe('FAILED');
    expect(r.state.error).toBe('MODEL_TIMEOUT');
  });

  it('reaches STOPPED', () => {
    const r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'run.stopped', {});
    expect(r.state.status).toBe('STOPPED');
  });

  it('records checkpoint reason', () => {
    const r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'run.checkpointed', { reason: 'MAX_STEPS' });
    expect(r.state.status).toBe('CHECKPOINTED');
    expect(r.state.checkpointReason).toBe('MAX_STEPS');
  });

  it('appends to the capability timeline', () => {
    let r = applyEvent(INITIAL_RUN_STATE, ctx(), 1, 'step.started', { capability: 'model.intent_classify' });
    r = applyEvent(r.state, { lastSeq: r.lastSeq }, 2, 'step.completed', { capability: 'strategy.validate_draft' });
    expect(r.state.timeline).toHaveLength(2);
    expect(r.state.timeline[0].capability).toBe('model.intent_classify');
    expect(r.state.timeline[1].capability).toBe('strategy.validate_draft');
  });
});
