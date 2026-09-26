import { it, expect, vi, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import WorkbenchPage from '../../src/features/WorkbenchPage.vue';
import MessageList from '../../src/features/workspace/MessageList.vue';
import RunInspector from '../../src/features/runs/RunInspector.vue';
import ApprovalCard from '../../src/features/approvals/ApprovalCard.vue';
import { workspace } from '../../src/state/workspace';
import { approvalsApi } from '../../src/api/approvals';
import { runsApi } from '../../src/api/runs';
import type { Approval, RunSnapshot } from '../../src/domain/contracts';
const captured = vi.hoisted(() => ({ hook: null as null | ((s: RunSnapshot) => Promise<void>), run: null as null | ReturnType<typeof import('../../src/composables/useActiveRun').useActiveRun> }));
vi.mock('../../src/api/ui-context', () => ({ getUiContext: async () => ({ mode: 'OFFLINE_FIXTURE', modelLabel: 'test', ragEnabled: false }) }));
vi.mock('../../src/composables/useActiveRun', async () => {
  const { ref } = await import('vue');
  return { useActiveRun: (_done: unknown, hook: (s: RunSnapshot) => Promise<void>) => {
    captured.hook = hook;
    captured.run = { taskId: ref(null), snapshot: ref(null), steps: ref([]), summaries: ref([]), state: ref('closed'), error: ref(''), open: vi.fn(async () => undefined), stop: vi.fn(), cancel: vi.fn(async () => undefined) };
    return captured.run;
  } };
});
const snap = (id: string): RunSnapshot => ({ taskId: id, runId: id, sessionId: 's', status: 'SUCCEEDED', input: 'q', finalAnswer: 'a', createdAt: '', updatedAt: '', startedAt: null, finishedAt: null, cancelRequested: false, terminationReason: 'COMPLETED', runtimeReason: null, errorCode: null, recordingComplete: true, usage: null, requireEvidence: false, citations: [] });
async function setup() {
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }) });
  vi.spyOn(workspace, 'loadSessions').mockResolvedValue();
  vi.spyOn(workspace, 'select').mockImplementation(async id => { workspace.session.value = id ? { sessionId: id, title: id, createdAt: '', lastTurnSequence: '1', activeTaskId: null } : null; });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/sessions/:sessionId', component: WorkbenchPage }, { path: '/new', component: WorkbenchPage }] });
  await router.push('/sessions/s'); await router.isReady();
  const wrapper = mount(WorkbenchPage, { global: { plugins: [router], stubs: { SessionSidebar: true, WelcomePanel: true, Composer: true, MessageList: { name: 'MessageList', template: '<div><slot name="approvals"/></div>', emits: ['source', 'inspect'] }, RunInspector: true, ApprovalCard: true, BrandMark: true } } });
  await flushPromises(); return { wrapper, router };
}
afterEach(() => workspace.clear());
it('discards approval responses for a superseded run in the same session', async () => {
  let finish!: (values: Approval[]) => void;
  vi.spyOn(approvalsApi, 'list').mockImplementation(async id => id === 'old' ? new Promise(resolve => { finish = resolve; }) : []);
  const { wrapper } = await setup();
  captured.run!.taskId.value = 'old'; captured.run!.snapshot.value = snap('old');
  const pending = captured.hook!(snap('old'));
  captured.run!.taskId.value = 'new'; captured.run!.snapshot.value = snap('new'); await captured.hook!(snap('new'));
  finish([{ approvalId: 'stale', taskId: 'old' } as Approval]); await pending; await flushPromises();
  expect(wrapper.findAllComponents(ApprovalCard)).toHaveLength(0); wrapper.unmount();
});
it('aborts superseded inspection and never substitutes the active run while history loads', async () => {
  let finish!: (value: RunSnapshot) => void; const signals: AbortSignal[] = [];
  vi.spyOn(runsApi, 'snapshot').mockImplementation(async (id, signal) => { signals.push(signal!); return id === 'old' ? new Promise(resolve => { finish = resolve; }) : new Promise(() => undefined); });
  vi.spyOn(runsApi, 'steps').mockResolvedValue([]);
  const { wrapper, router } = await setup(); captured.run!.snapshot.value = snap('active');
  wrapper.findComponent(MessageList).vm.$emit('source', 'old', 'S1'); await flushPromises();
  expect(wrapper.findComponent(RunInspector).props('snapshot')).toBeNull();
  wrapper.findComponent(MessageList).vm.$emit('inspect', 'other'); await flushPromises();
  expect(signals[0]!.aborted).toBe(true);
  await router.push('/new'); await flushPromises(); finish(snap('old')); await flushPromises();
  expect(wrapper.findComponent(RunInspector).props('tab')).toBe('execution'); wrapper.unmount();
});
it('keeps unavailable historical detail separate from the current run', async () => {
  vi.spyOn(runsApi, 'snapshot').mockRejectedValue(new Error('unavailable')); vi.spyOn(runsApi, 'steps').mockResolvedValue([]);
  const { wrapper } = await setup(); captured.run!.snapshot.value = snap('active');
  wrapper.findComponent(MessageList).vm.$emit('inspect','missing'); await flushPromises();
  expect(wrapper.findComponent(RunInspector).props('snapshot')).toBeNull(); wrapper.unmount();
});
it('ignores an aborted approval read even when a newer read belongs to the same run', async () => {
  let finish!: (values: Approval[]) => void;
  const read = vi.spyOn(approvalsApi, 'list').mockImplementationOnce(async () => new Promise(resolve => { finish = resolve; })).mockResolvedValue([]);
  const { wrapper } = await setup(); captured.run!.taskId.value = 'same'; captured.run!.snapshot.value = snap('same');
  const pending = captured.hook!(snap('same')); await captured.hook!(snap('same'));
  expect(read.mock.calls[0]![1]!.aborted).toBe(true);
  finish([{ approvalId: 'stale', taskId: 'same' } as Approval]); await pending; await flushPromises();
  expect(wrapper.findAllComponents(ApprovalCard)).toHaveLength(0); wrapper.unmount();
});
