import { describe, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import MemoryPanel from '../../src/features/memory/MemoryPanel.vue';
import { memoriesApi } from '../../src/api/memories';
const slot = { sessionId: 's', key: 'preferred_language' as const, type: 'STRING', state: 'PRESENT', value: '中文', version: '9007199254740993', source: 'USER_CONFIRMED', updatedAt: null };
describe('confirmed memory editing', () => {
  it('uses string versions and validates length before writes', async () => {
    vi.spyOn(memoriesApi, 'get').mockResolvedValue([slot]); const put = vi.spyOn(memoriesApi, 'put').mockResolvedValue({ ...slot, version: '9007199254740994' });
    const w = mount(MemoryPanel, { props: { sessionId: 's' } }); await flushPromises();
    await w.get('textarea').setValue('English'); await w.get('[data-action=save]').trigger('click'); await flushPromises();
    expect(put).toHaveBeenCalledWith('s', 'preferred_language', 'English', '9007199254740993');
    await w.get('textarea').setValue('x'.repeat(513)); expect(w.get('[data-action=save]').attributes('disabled')).toBeDefined();
  });
  it('requires a real session before reading or editing', () => {
    const get = vi.spyOn(memoriesApi, 'get'); const w = mount(MemoryPanel, { props: { sessionId: null } });
    expect(get).not.toHaveBeenCalled(); expect(w.text()).toContain('先开始一个会话');
  });
});
