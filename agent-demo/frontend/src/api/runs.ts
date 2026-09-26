import { acceptedRun, runSnapshot, list, object, integer, text, nullableText, bool, type Step } from '../domain/contracts';
import { http } from './http';
export const runPath = (id: string) => `/api/agent/tasks/${encodeURIComponent(id)}`;
export function step(value: unknown): Step {
  const v = object(value);
  return { stepNo: integer(v.stepNo), stepType: text(v.stepType, 128), toolName: nullableText(v.toolName), input: nullableText(v.input), output: nullableText(v.output), status: text(v.status, 128), latencyMs: integer(v.latencyMs), promptTokens: v.promptTokens === null ? null : integer(v.promptTokens), completionTokens: v.completionTokens === null ? null : integer(v.completionTokens), errorMessage: nullableText(v.errorMessage), decisionId: nullableText(v.decisionId), callId: nullableText(v.callId), errorCode: nullableText(v.errorCode), terminal: bool(v.terminal) };
}
export const runsApi = {
  async submit(input: string, sessionId: string | null, requireEvidence: boolean) { return acceptedRun(await http.json('/api/agent/tasks', { method: 'POST', body: JSON.stringify({ input, requireEvidence, ...(sessionId ? { sessionId } : {}) }) })); },
  async snapshot(id: string, signal?: AbortSignal) { return runSnapshot(await http.json(runPath(id), { signal })); },
  async steps(id: string, signal?: AbortSignal) { return list(await http.json(runPath(id) + '/steps', { signal }), step, 256); },
  async cancel(id: string) { return runSnapshot(await http.json(runPath(id) + '/cancel', { method: 'POST' })); },
};
