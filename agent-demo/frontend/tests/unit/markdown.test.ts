import { describe, it, expect } from 'vitest';
import { renderMarkdown } from '../../src/security/markdown';
describe('untrusted answer rendering', () => {
  it('blocks html, scripts, active SVG and external images', () => {
    const result = renderMarkdown('<script>alert(1)</script><svg onload="alert(1)"></svg> ![x](https://remote.invalid/a.png) [bad](javascript:alert(1))');
    expect(result).not.toMatch(/<script|<svg|<img|href="javascript:/i);
  });
  it('allows tables/code/safe links while securing new windows', () => {
    const result = renderMarkdown('| a | b |\n|---|---|\n| 1 | 2 |\n\n```java\nclass A {}\n```\n[docs](https://example.com)');
    expect(result).toContain('<table>'); expect(result).toContain('<pre>'); expect(result).toContain('rel="noopener noreferrer"');
  });
  it('does not convert code citations into links and rejects oversized content', () => {
    expect(renderMarkdown('`[S1]`')).toContain('<code>[S1]</code>');
    expect(() => renderMarkdown('x'.repeat(65537))).toThrow();
  });
});
