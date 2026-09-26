import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Composer from '../../src/features/workspace/Composer.vue';
const props = { text: '你好', evidence: false, evidenceEnabled: true, busy: false, active: false, uncertain: false };
describe('composer deliberate submission', () => {
  it('never submits while composing or Shift+Enter and emits once for Enter', async () => {
    const w = mount(Composer, { props }); const input = w.get('textarea');
    await input.trigger('compositionstart'); await input.trigger('keydown', { key: 'Enter' });
    expect(w.emitted('submit')).toBeUndefined(); await input.trigger('compositionend');
    await input.trigger('keydown', { key: 'Enter', shiftKey: true }); expect(w.emitted('submit')).toBeUndefined();
    await input.trigger('keydown', { key: 'Enter' }); expect(w.emitted('submit')).toHaveLength(1);
  });
  it('locks sending for active tasks, uncertainty and oversized input', async () => {
    for (const extra of [{ active: true }, { uncertain: true }, { text: 'x'.repeat(8001) }]) {
      const w = mount(Composer, { props: { ...props, ...extra } });
      await w.get('textarea').trigger('keydown', { key: 'Enter' }); expect(w.emitted('submit')).toBeUndefined();
    }
  });
});
