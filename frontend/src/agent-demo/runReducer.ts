// Pure SSE event reducer for the Agent Demo. Takes the current RunState and
// one event envelope, returns the next RunState. Handles: seq dedup, unknown
// events still advance seq, assistant.delta append, terminal states, intent
// fields. No I/O — fully unit-testable.

import type { RunState, TimelineEntry } from './types';
import { isTerminal } from './types';

export const INITIAL_RUN_STATE: RunState = {
  runId: null,
  status: 'IDLE',
  stepsUsed: 0,
  toolCallsUsed: 0,
  modelCallsUsed: 0,
  maxSteps: 8,
  maxToolCalls: 12,
  intent: null,
  intentLabels: [],
  authorization: null,
  calibratedConfidence: null,
  ambiguities: [],
  requiresConfirmation: false,
  nextQuestion: null,
  deltaText: '',
  checkpointReason: null,
  modelUnavailable: false,
  error: null,
  timeline: [],
};

export interface ReducerContext {
  lastSeq: number;
}

export interface ReducerResult {
  state: RunState;
  lastSeq: number;
  applied: boolean;
}

/**
 * Apply one event. Returns the new state + seq. Events at or below lastSeq are
 * ignored (dedup). Unknown event types still advance lastSeq but do not mutate
 * the run state.
 */
export function applyEvent(
  state: RunState,
  ctx: ReducerContext,
  seq: number,
  type: string,
  payload: Record<string, unknown>,
): ReducerResult {
  if (seq <= ctx.lastSeq) {
    return { state, lastSeq: ctx.lastSeq, applied: false };
  }
  // If the run is already terminal, ignore non-terminal events (but still
  // advance seq so the client tracks the high-water mark).
  if (isTerminal(state.status) && !isTerminalEventType(type)) {
    return { state, lastSeq: seq, applied: true };
  }

  const entry: TimelineEntry = {
    seq,
    type,
    capability: typeof payload.capability === 'string' ? (payload.capability as string) : null,
    at: typeof payload.at === 'string' ? (payload.at as string) : new Date().toISOString(),
  };

  let next: RunState = { ...state, timeline: [...state.timeline, entry] };

  switch (type) {
    case 'turn.accepted':
      next.status = 'QUEUED';
      if (typeof payload.runId === 'string') next.runId = payload.runId as string;
      break;
    case 'run.started':
      next.status = 'RUNNING';
      break;
    case 'intent.detected':
      next.intent = (payload.primaryIntent as string) ?? next.intent;
      next.intentLabels = Array.isArray(payload.labels)
        ? (payload.labels as string[])
        : next.intentLabels;
      next.authorization = (payload.authorization as string) ?? next.authorization;
      next.calibratedConfidence =
        typeof payload.calibratedConfidence === 'number'
          ? (payload.calibratedConfidence as number)
          : next.calibratedConfidence;
      next.requiresConfirmation =
        typeof payload.requiresConfirmation === 'boolean'
          ? (payload.requiresConfirmation as boolean)
          : next.requiresConfirmation;
      next.nextQuestion = (payload.nextQuestion as string) ?? next.nextQuestion;
      if (Array.isArray(payload.ambiguities)) {
        next.ambiguities = payload.ambiguities as string[];
      }
      next.modelUnavailable =
        typeof payload.modelUnavailable === 'boolean'
          ? (payload.modelUnavailable as boolean)
          : next.modelUnavailable;
      break;
    case 'step.started':
    case 'step.completed':
      if (typeof payload.stepsUsed === 'number') {
        next.stepsUsed = payload.stepsUsed as number;
      }
      break;
    case 'budget.updated':
      if (typeof payload.stepsUsed === 'number') next.stepsUsed = payload.stepsUsed as number;
      if (typeof payload.toolCallsUsed === 'number')
        next.toolCallsUsed = payload.toolCallsUsed as number;
      if (typeof payload.maxSteps === 'number') next.maxSteps = payload.maxSteps as number;
      if (typeof payload.maxToolCalls === 'number')
        next.maxToolCalls = payload.maxToolCalls as number;
      break;
    case 'assistant.delta':
      if (typeof payload.text === 'string') {
        next.deltaText += payload.text as string;
      } else if (typeof payload.nextQuestion === 'string') {
        next.deltaText += (payload.nextQuestion as string) + '\n';
        next.nextQuestion = payload.nextQuestion as string;
      }
      break;
    case 'seed.detected':
      // The seed is reflected via session detail refresh; nothing to mutate here.
      break;
    case 'draft.updated':
      // The draft is reflected via session detail refresh.
      break;
    case 'checkpoint.saved':
    case 'run.checkpointed':
      next.status = 'CHECKPOINTED';
      next.checkpointReason =
        (payload.reason as string) ?? (payload.checkpointReason as string) ?? 'BUDGET_EXHAUSTED';
      break;
    case 'run.completed':
      next.status = 'COMPLETED';
      break;
    case 'run.failed':
      next.status = 'FAILED';
      next.error = (payload.error as string) ?? (payload.reason as string) ?? 'FAILED';
      break;
    case 'run.stopped':
    case 'turn.stopped':
      next.status = 'STOPPED';
      break;
    default:
      // Unknown event type: advance seq but do not mutate state.
      break;
  }

  return { state: next, lastSeq: seq, applied: true };
}

function isTerminalEventType(type: string): boolean {
  return (
    type === 'run.completed' ||
    type === 'run.failed' ||
    type === 'run.stopped' ||
    type === 'run.checkpointed' ||
    type === 'turn.stopped'
  );
}
