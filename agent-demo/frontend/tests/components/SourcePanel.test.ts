import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import SourcePanel from '../../src/features/evidence/SourcePanel.vue';
import { renderMarkdown } from '../../src/security/markdown';
describe('run-bound sources', () => {
  it('renders source paths as plain text and never as local navigation', () => {
    const w = mount(SourcePanel, { props: { citations: [{ id: 'S1', title: 'Doc', sourcePath: 'file:///private/a.md', snapshotId: 'snap', docId: 'doc', documentVersion: '1', chunkId: 'chunk', contentHash: 'hash', start: 1, end: 3, excerpt: 'Java 文档' }], runId: 'one' } });
    expect(w.text()).toContain('file:///private/a.md'); expect(w.find('a').exists()).toBe(false);
  });
  it('binds only supplied IDs outside code blocks', () => {
    const html = renderMarkdown('来源 [S1] 和 [S2] `code [S1]`', ['S1']);
    expect(html).toContain('data-source-id="S1"'); expect(html).not.toContain('data-source-id="S2"'); expect(html).toContain('<code>code [S1]</code>');
  });
});
