// Agent Demo frontend types — mirror the backend Stable Demo Contract verbatim.
// No field uses a wide `Record<string, unknown>` for core run state.

export interface AcceptedTurnResponse {
  schema: 'agent-demo.accepted.v1';
  sessionId: string;
  turnId: string;
  runId: string;
  status: string;
  eventSeq: number;
}

export interface DemoEventEnvelope {
  schema: 'agent-demo.event.v1';
  seq: number;
  eventId: string;
  sessionId: string;
  runId: string | null;
  type: string;
  at: string;
  payload: Record<string, unknown>;
}

export interface DemoTurnView {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  intent: string | null;
  modelUnavailable: boolean;
  seed: DemoSeedView | null;
  createdAt: string;
}

export interface DemoRunView {
  id: string;
  intent: string;
  intentLabels: string[] | null;
  authorization: string | null;
  calibratedConfidence: number | null;
  requiresConfirmation: boolean | null;
  status: string;
  stepsUsed: number;
  maxSteps: number;
  toolCallsUsed: number;
  maxToolCalls: number;
  modelCallsUsed: number;
  maxModelCalls: number;
  outputCharsUsed: number;
  elapsedMs: number;
  modelUnavailable: boolean;
  error: string | null;
  checkpointReason: string | null;
  createdAt: string;
}

export interface DemoStepView {
  runId: string | null;
  seq: number;
  kind: string;
  capability: string | null;
  summary: string;
  durationMs: number;
  createdAt: string;
}

export interface DemoSeedView {
  id: string;
  summary: string;
  status: string;
  sourceTurnId: string | null;
}

export interface DemoEvidenceView {
  fieldPath: string;
  sourceType: string;
  sourceId: string;
  productionEvidence: boolean;
  excerpt?: string;
}

export interface DemoDraftView {
  id: string;
  status: string;
  version: number;
  completeness: number;
  missingFields: string[];
  fields: Record<string, unknown>;
  evidence: DemoEvidenceView[];
  nextQuestion: string | null;
  frozen: boolean;
}

export interface DemoSessionSummary {
  id: string;
  title: string;
  status: string;
  createdAt: string;
  lastEventSeq: number;
}

export interface DemoSessionDetail {
  id: string;
  workspaceId: string;
  title: string;
  status: string;
  turns: DemoTurnView[];
  runs: DemoRunView[];
  stepsByRun: DemoStepView[];
  seeds: DemoSeedView[];
  activeDraft: DemoDraftView | null;
  lastEventSeq: number;
}

export interface ApiError {
  code: string;
  message: string;
}

/** A run's live state derived from events + session detail. */
export interface RunState {
  runId: string | null;
  status: string;
  stepsUsed: number;
  toolCallsUsed: number;
  modelCallsUsed: number;
  maxSteps: number;
  maxToolCalls: number;
  intent: string | null;
  intentLabels: string[];
  authorization: string | null;
  calibratedConfidence: number | null;
  ambiguities: string[];
  requiresConfirmation: boolean;
  nextQuestion: string | null;
  deltaText: string;
  checkpointReason: string | null;
  modelUnavailable: boolean;
  error: string | null;
  timeline: TimelineEntry[];
}

export interface TimelineEntry {
  seq: number;
  type: string;
  capability: string | null;
  at: string;
}

export const TERMINAL_STATUSES = new Set([
  'COMPLETED',
  'FAILED',
  'CHECKPOINTED',
  'STOPPED',
  'CANCELLED',
]);

export function isTerminal(status: string | null | undefined): boolean {
  return !!status && TERMINAL_STATUSES.has(status);
}
