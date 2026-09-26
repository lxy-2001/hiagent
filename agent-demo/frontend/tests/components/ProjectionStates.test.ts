import { it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import RunInspector from '../../src/features/runs/RunInspector.vue';
import MessageList from '../../src/features/workspace/MessageList.vue';
import type { RunSnapshot, Step } from '../../src/domain/contracts';
const snapshot: RunSnapshot = { taskId: 'r', runId: 'r', sessionId: 's', status: 'SUCCEEDED', input: 'q', finalAnswer: 'a', createdAt: '', updatedAt: '', startedAt: null, finishedAt: null, cancelRequested: false, terminationReason: 'COMPLETED', runtimeReason: null, errorCode: null, recordingComplete: true, usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0 }, requireEvidence: false, citations: [] };
const props = { snapshot, steps: [] as Step[], summaries: [], state: 'closed' as const, sessionId: 's', tab: 'execution' as const };
it('displays reported zero usage rather than calling it unavailable', () => { const w = mount(RunInspector, { props }); expect(w.text()).toContain('合计 0 tokens'); w.unmount(); });
it('distinguishes absent evidence, known empty retrieval and retrieval failure', async () => {
  const w = mount(RunInspector, { props: { ...props, tab: 'sources' } }); expect(w.text()).toContain('没有可核对的引用');
  await w.setProps({ steps: [{ stepType: 'RETRIEVAL', toolName: 'knowledge.search', output: 'mode=HYBRID hits=0 kept=0 trimmed=0 empty=NO_MATCH', errorCode: null } as Step] });
  expect(w.text()).toContain('未检索到可用文档');
  await w.setProps({ steps: [{ toolName: 'knowledge.search', errorCode: 'RAG_VECTOR_UNAVAILABLE' } as Step] });
  expect(w.text()).toContain('检索服务暂不可用'); w.unmount();
});
it('keeps incomplete recording visible in historical turns', () => {
  const w = mount(MessageList, { props: { turns: [{ runId: 'r', sessionId: 's', turnSequence: '1', status: 'SUCCEEDED', input: 'q', finalAnswer: 'a', terminationReason: 'COMPLETED', recordingComplete: false, contentUnavailable: false, createdAt: '', finishedAt: '' }], snapshot: null, older: false, loading: false, loadOlder: async () => undefined } });
  expect(w.text()).toContain('部分执行记录暂不完整'); w.unmount();
});
