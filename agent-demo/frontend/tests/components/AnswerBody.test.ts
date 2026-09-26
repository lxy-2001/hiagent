import { describe, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import AnswerBody from '../../src/features/workspace/AnswerBody.vue';
import { useScrollAnchor } from '../../src/composables/useScrollAnchor';
import { ref } from 'vue';
describe('answer copy and reading position', () => {
  it('explains clipboard rejection instead of claiming success', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true });
    const w = mount(AnswerBody, { props: { text: 'answer' } }); await w.get('button').trigger('click'); await flushPromises();
    expect(w.get('[role=status]').text()).toContain('无法访问剪贴板');
  });
  it('keeps the same visible anchor when prepending older messages', async () => {
    const node = document.createElement('div'); let height = 1000; Object.defineProperty(node, 'scrollHeight', { get: () => height }); node.scrollTop = 80;
    await useScrollAnchor(ref(node)).prepend(async () => { height = 1500; }); expect(node.scrollTop).toBe(580);
  });
});
