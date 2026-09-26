import { ref, shallowRef } from 'vue';
import { http } from '../api/http';
import { runsApi, step } from '../api/runs';
import { runSnapshot, terminal, list, object, type RunSnapshot, type Step } from '../domain/contracts';
import { observeRun, type ObserverState } from './useRunObserver';
import { ApiError } from '../api/response';
export function useActiveRun(onTerminal: () => Promise<void>, onSnapshot?: (value: RunSnapshot) => Promise<void>) {
  const snapshot = shallowRef<RunSnapshot | null>(null), steps = shallowRef<Step[]>([]), summaries = ref<string[]>([]), state = ref<ObserverState>('closed'), error = ref('');
  let observer: ReturnType<typeof observeRun> | null = null, generation = 0, controller = new AbortController();
  let lastTerminal: string | null = null;
  async function accept(value: unknown, expected: number) {
    if (expected !== generation) return;
    const parsed = runSnapshot(value); snapshot.value = parsed;
    await onSnapshot?.(parsed);
    if (terminal(parsed.status) && lastTerminal !== parsed.runId) { lastTerminal = parsed.runId; await onTerminal(); }
  }
  async function open(id: string) {
    stop(); const expected = ++generation; controller = new AbortController(); snapshot.value = null; steps.value = []; summaries.value = []; error.value = ''; state.value = 'connecting';
    try {
      const initial = await runsApi.snapshot(id, controller.signal); if (expected !== generation) return; await accept(initial, expected);
      if (terminal(initial.status)) { const items = await runsApi.steps(id, controller.signal); if (expected === generation) { steps.value = items; state.value = 'closed'; } return; }
      observer = observeRun({ taskId: id, get: http.response, onState: value => { if (expected === generation) state.value = value; },
        onSnapshot: value => accept(value, expected), onSteps: value => { if (expected === generation) steps.value = list(value, step, 256); },
        onEvent: async event => {
          if (expected !== generation) return;
          if (event.type === 'AGENT_STEP') { const payload = object(event.payload); if (typeof payload.summary === 'string' && summaries.value.length < 256) summaries.value.push(payload.summary.slice(0, 4096)); }
          if (event.type !== 'RUN_TERMINATED') { const value = await runsApi.snapshot(id, controller.signal); await accept(value, expected); }
        } });
      await observer.start();
    } catch (e) { if (expected === generation) { error.value = e instanceof ApiError ? e.message : '暂时无法观察任务，请重新连接。'; state.value = 'manual-refresh'; } }
  }
  function stop() { generation++; observer?.stop(); observer = null; controller.abort(); state.value = 'paused'; }
  async function cancel() { if (!snapshot.value || terminal(snapshot.value.status)) return; const expected = generation;
    try { const value = await runsApi.cancel(snapshot.value.taskId); await accept(value, expected); }
    catch (e) { if (expected === generation) error.value = e instanceof ApiError ? e.message : '取消结果未确认，请读取最新任务状态。'; }
  }
  return { snapshot, steps, summaries, state, error, open, stop, cancel };
}
