import { describe, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ApprovalCard from '../../src/features/approvals/ApprovalCard.vue';
import type { Approval } from '../../src/domain/contracts';
const base: Approval = { approvalId: 'a', taskId: 'r', runId: 'r', callId: 'c', toolName: 'note', serverId: 'demo', remoteToolName: 'note', risk: 'HIGH', actionSummary: '写入演示笔记', argumentPreview: [{ name: 'secret', value: '***', masked: true, truncated: true }], argumentsDigest: 'digest', definitionVersion: '1', policyVersion: '1', status: 'PENDING', createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 60000).toISOString(), decidedAt: null, decisionSource: null, dispatchCount: 0, outcome: 'NOT_DISPATCHED', waitMillis: null };
describe('approval authorization is distinct from execution', () => {
  it('locks duplicate decisions and preserves masked/truncated explanations', async () => {
    let done!: () => void; const decide = vi.fn(() => new Promise<void>(resolve => { done = resolve; }));
    const w = mount(ApprovalCard, { props: { approval: base, decide } });
    const approve = w.get('[data-decision=APPROVE]'); await approve.trigger('click'); await approve.trigger('click');
    expect(decide).toHaveBeenCalledTimes(1); expect(w.text()).toContain('已遮蔽'); expect(w.text()).toContain('已截断');
    done(); await flushPromises();
  });
  it.each(['EXPIRED', 'INVALIDATED', 'CANCELLED'] as const)('does not allow decisions for %s', status => {
    const w = mount(ApprovalCard, { props: { approval: { ...base, status }, decide: vi.fn() } }); expect(w.find('[data-decision=APPROVE]').exists()).toBe(false);
  });
  it('does not claim success for APPROVED or UNKNOWN and never offers replay', () => {
    const w = mount(ApprovalCard, { props: { approval: { ...base, status: 'APPROVED', outcome: 'UNKNOWN', dispatchCount: 1 }, decide: vi.fn() } });
    expect(w.text()).toContain('结果未知'); expect(w.text()).toContain('取消不能撤销'); expect(w.find('[data-decision=APPROVE]').exists()).toBe(false);
  });
});
