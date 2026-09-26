import { decimal } from './decimal';

export const RUN_STATUSES = ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'BUDGET_EXCEEDED'] as const;
export type RunStatus = typeof RUN_STATUSES[number];
export const terminal = (status: RunStatus): boolean => !['QUEUED', 'RUNNING', 'WAITING_APPROVAL'].includes(status);
export interface Conversation { sessionId: string; title: string; createdAt: string; lastTurnSequence: string; activeTaskId: string | null }
export interface ConversationPage { items: Conversation[]; hasMore: boolean; nextBefore: string | null }
export interface Turn { runId: string; sessionId: string; turnSequence: string; status: RunStatus; input: string | null; finalAnswer: string | null; terminationReason: string | null; recordingComplete: boolean; contentUnavailable: boolean; createdAt: string; finishedAt: string | null }
export interface HistoryPage { items: Turn[]; hasMore: boolean; nextBeforeSequence: string | null; untilSequence: string }
export interface UiContext { mode: 'REAL_CONFIGURED' | 'OFFLINE_FIXTURE'; modelLabel: string | null; ragEnabled: boolean; mcpEnabled: boolean }
export interface Citation { id: string; snapshotId: string; docId: string; documentVersion: string; chunkId: string; contentHash: string; sourcePath: string; title: string; start: number; end: number; excerpt: string }
export interface Usage { promptTokens: number; completionTokens: number; totalTokens: number }
export interface RunSnapshot { taskId: string; runId: string; sessionId: string; status: RunStatus; input: string; finalAnswer: string | null; createdAt: string; updatedAt: string; startedAt: string | null; finishedAt: string | null; cancelRequested: boolean; terminationReason: string | null; runtimeReason: string | null; errorCode: string | null; recordingComplete: boolean; usage: Usage | null; requireEvidence: boolean; citations: Citation[] }
export interface AcceptedRun { taskId: string; runId: string; sessionId: string; status: RunStatus }
export interface Step { stepNo: number; stepType: string; toolName: string | null; input: string | null; output: string | null; status: string; latencyMs: number; promptTokens: number | null; completionTokens: number | null; errorMessage: string | null; decisionId: string | null; callId: string | null; errorCode: string | null; terminal: boolean }
export const APPROVAL_STATUSES = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'INVALIDATED', 'INTERRUPTED'] as const;
export type ApprovalStatus = typeof APPROVAL_STATUSES[number];
export interface Approval { approvalId: string; taskId: string; runId: string; callId: string; toolName: string; serverId: string | null; remoteToolName: string | null; risk: string; actionSummary: string; argumentPreview: { name: string; value: string; masked: boolean; truncated: boolean }[]; argumentsDigest: string; definitionVersion: string; policyVersion: string; status: ApprovalStatus; createdAt: string; expiresAt: string; decidedAt: string | null; decisionSource: string | null; dispatchCount: number; outcome: 'NOT_DISPATCHED' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'; waitMillis: number | null }
export interface MemorySlot { sessionId: string; key: 'preferred_language' | 'project_stack'; type: string; state: string; value: string | null; version: string; source: string | null; updatedAt: string | null }

export function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('INVALID_RESPONSE');
  return value as Record<string, unknown>;
}
export function text(value: unknown, max = 65536): string {
  if (typeof value !== 'string' || value.length > max) throw new Error('INVALID_RESPONSE');
  return value;
}
export function nullableText(value: unknown, max = 65536): string | null { return value === null ? null : text(value, max); }
export function bool(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('INVALID_RESPONSE');
  return value;
}
export function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('INVALID_RESPONSE');
  return value;
}
export function list<T>(value: unknown, parse: (item: unknown) => T, maximum: number): T[] {
  if (!Array.isArray(value) || value.length > maximum) throw new Error('INVALID_RESPONSE');
  return value.map(parse);
}
export function enumeration<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) throw new Error('INVALID_RESPONSE');
  return value as T;
}
export function conversation(value: unknown): Conversation {
  const v = object(value);
  return { sessionId: text(v.sessionId, 36), title: text(v.title, 200), createdAt: text(v.createdAt, 64), lastTurnSequence: decimal(v.lastTurnSequence), activeTaskId: nullableText(v.activeTaskId, 36) };
}
export function conversationPage(value: unknown): ConversationPage {
  const v = object(value);
  return { items: list(v.items, conversation, 50), hasMore: bool(v.hasMore), nextBefore: nullableText(v.nextBefore, 512) };
}
export function turn(value: unknown): Turn {
  const v = object(value);
  const status = enumeration(v.status, RUN_STATUSES);
  if (!terminal(status)) throw new Error('INVALID_RESPONSE');
  return { runId: text(v.runId, 36), sessionId: text(v.sessionId, 36), turnSequence: decimal(v.turnSequence), status, input: nullableText(v.input, 8000), finalAnswer: nullableText(v.finalAnswer), terminationReason: nullableText(v.terminationReason, 100), recordingComplete: bool(v.recordingComplete), contentUnavailable: bool(v.contentUnavailable), createdAt: text(v.createdAt, 64), finishedAt: nullableText(v.finishedAt, 64) };
}
export function historyPage(value: unknown): HistoryPage {
  const v = object(value);
  const result = { items: list(v.items, turn, 50), hasMore: bool(v.hasMore), nextBeforeSequence: v.nextBeforeSequence == null ? null : decimal(v.nextBeforeSequence), untilSequence: decimal(v.untilSequence) };
  if (result.hasMore && (result.nextBeforeSequence === null || result.items.length === 0)) throw new Error('INVALID_RESPONSE');
  return result;
}
export function uiContext(value: unknown): UiContext {
  const v = object(value);
  return { mode: enumeration(v.mode, ['REAL_CONFIGURED', 'OFFLINE_FIXTURE']), modelLabel: nullableText(v.modelLabel, 128), ragEnabled: bool(v.ragEnabled), mcpEnabled: bool(v.mcpEnabled) };
}
export function citation(value: unknown): Citation {
  const v = object(value);
  const id = text(v.id, 3);
  if (!/^S([1-9]|[12][0-9]|3[0-2])$/.test(id)) throw new Error('INVALID_RESPONSE');
  return { id, snapshotId: text(v.snapshotId), docId: text(v.docId), documentVersion: text(v.documentVersion), chunkId: text(v.chunkId), contentHash: text(v.contentHash), sourcePath: text(v.sourcePath), title: text(v.title), start: integer(v.start), end: integer(v.end), excerpt: text(v.excerpt) };
}
export function runSnapshot(value: unknown): RunSnapshot {
  const v = object(value);
  const ids = acceptedRun(v);
  const u = v.usage == null ? null : object(v.usage);
  return { ...ids, input: text(v.input, 8000), finalAnswer: nullableText(v.finalAnswer), createdAt: text(v.createdAt, 64), updatedAt: text(v.updatedAt, 64), startedAt: nullableText(v.startedAt, 64), finishedAt: nullableText(v.finishedAt, 64), cancelRequested: bool(v.cancelRequested), terminationReason: nullableText(v.terminationReason, 100), runtimeReason: nullableText(v.runtimeReason, 100), errorCode: nullableText(v.errorCode, 100), recordingComplete: bool(v.recordingComplete), usage: u ? { promptTokens: integer(u.promptTokens), completionTokens: integer(u.completionTokens), totalTokens: integer(u.totalTokens) } : null, requireEvidence: bool(v.requireEvidence), citations: list(v.citations, citation, 32) };
}
export function acceptedRun(value: unknown): AcceptedRun {
  const v = object(value);
  const taskId = text(v.taskId, 36), runId = text(v.runId, 36);
  if (!taskId || taskId !== runId) throw new Error('INVALID_RESPONSE');
  return { taskId, runId, sessionId: text(v.sessionId, 36), status: enumeration(v.status, RUN_STATUSES) };
}
