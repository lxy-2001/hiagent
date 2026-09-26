import { createSseParser, type RunEvent } from './sse-parser';
import { ApiError } from '../api/response';
export type ObserverState = 'connecting' | 'live' | 'reconnecting' | 'polling' | 'paused' | 'manual-refresh' | 'unauthorized' | 'closed';
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'BUDGET_EXCEEDED']);
export function observeRun(options: {
  taskId: string; get: (url: string, init?: RequestInit) => Promise<Response>;
  onSnapshot: (snapshot: unknown) => unknown; onSteps: (steps: unknown) => unknown;
  onState: (state: ObserverState) => void; onEvent?: (event: RunEvent) => unknown;
  sleep?: (milliseconds: number) => Promise<void>;
}) {
  const controller = new AbortController(); let stopped = false, cursor = '0';
  const state = (value: ObserverState) => { if (!stopped) options.onState(value); };
  async function sleep(ms: number) {
    if (stopped) return;
    if (options.sleep) return options.sleep(ms);
    await new Promise<void>(resolve => { const done = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', done); resolve(); }; const timer = setTimeout(done, ms); controller.signal.addEventListener('abort', done, { once: true }); });
  }
  const base = `/api/agent/tasks/${encodeURIComponent(options.taskId)}`;
  async function read(path: string, init: RequestInit = {}) { return options.get(path, { ...init, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(45000)]) }); }
  async function snapshot(): Promise<boolean> {
    const response = await read(base); if (stopped) return true;
    if ([401, 403, 404].includes(response.status)) { state(response.status === 401 ? 'unauthorized' : 'manual-refresh'); return true; }
    if (!response.ok) return false;
    const value: unknown = await response.json(); if (stopped) return true;
    await options.onSnapshot(value); if (stopped) return true;
    if (value && typeof value === 'object' && 'status' in value && terminal.has(String(value.status))) {
      const steps = await read(base + '/steps'); if (stopped) return true;
      if (!steps.ok) { state('manual-refresh'); return true; }
      const items: unknown = await steps.json(); if (!stopped) { await options.onSteps(items); state('closed'); }
      return true;
    }
    return false;
  }
  async function start() {
    const delays = [1000, 2000, 4000];
    for (let attempt = 0; attempt <= delays.length && !stopped; attempt++) {
      state(attempt ? 'reconnecting' : 'connecting');
      try {
        const response = await read(base + '/events', { headers: { Accept: 'text/event-stream', ...(cursor !== '0' ? { 'Last-Event-ID': cursor } : {}) } });
        if (stopped) return;
        if ([401, 403, 404, 400].includes(response.status)) { state(response.status === 401 ? 'unauthorized' : 'manual-refresh'); return; }
        if (response.status === 204 || response.status === 410) break;
        if (response.ok && response.body && response.headers.get('content-type')?.includes('text/event-stream')) {
          state('live'); let ended = false;
          const parser = createSseParser({ taskId: options.taskId, initialEventId: cursor, onEvent: async event => { if (!stopped) { await options.onEvent?.(event); ended = event.type === 'RUN_TERMINATED'; } } });
          const reader = response.body.getReader();
          try { while (!stopped && !ended) { const item = await reader.read(); if (item.done) break; await parser.push(item.value); cursor = parser.lastEventId(); } parser.finish(); }
          finally { await reader.cancel().catch(() => undefined); reader.releaseLock(); }
          if (stopped) return; if (await snapshot()) return; if (ended) break;
        }
      } catch (error) { if (stopped) return; if (error instanceof ApiError && (error.code === 'UNAUTHORIZED' || error.code === 'STALE_REQUEST')) { state('unauthorized'); return; } }
      if (attempt < delays.length) await sleep(delays[attempt]!);
    }
    state('polling');
    for (let attempt = 0; attempt < 30 && !stopped; attempt++) {
      try { if (await snapshot()) return; }
      catch (error) { if (error instanceof ApiError && ['UNAUTHORIZED', 'STALE_REQUEST'].includes(error.code)) { state('unauthorized'); return; } }
      if (attempt < 29) await sleep(2000);
    }
    state('manual-refresh');
  }
  return { start, stop() { if (!stopped) { stopped = true; controller.abort(); options.onState('paused'); } }, lastEventId: () => cursor };
}
