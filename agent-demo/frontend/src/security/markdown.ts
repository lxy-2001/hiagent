import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import json from 'highlight.js/lib/languages/json';
import javascript from 'highlight.js/lib/languages/javascript';
hljs.registerLanguage('java', java); hljs.registerLanguage('json', json); hljs.registerLanguage('javascript', javascript);
const md = new MarkdownIt({ html: false, linkify: false, typographer: false, highlight(code, language) { return language && hljs.getLanguage(language) ? hljs.highlight(code, { language, ignoreIllegals: true }).value : ''; } });
md.renderer.rules.image = (tokens, index) => `<span class="blocked-image">[图片已阻止：${md.utils.escapeHtml(tokens[index]!.content)}]</span>`;
md.renderer.rules.link_open = (tokens, index, options, _env, renderer) => { const token = tokens[index]!; const href = String(token.attrGet('href') ?? ''); if (!/^https?:\/\//i.test(href)) token.attrSet('href', '#'); token.attrSet('target', '_blank'); token.attrSet('rel', 'noopener noreferrer'); return renderer.renderToken(tokens, index, options); };
md.renderer.rules.text = (tokens, index, _options, env) => {
  const escaped = md.utils.escapeHtml(tokens[index]!.content);
  return escaped.replace(/\[(S(?:[1-9]|[12][0-9]|3[0-2]))\]/g, (match, id: string) => Array.isArray(env?.sources) && env.sources.includes(id) ? `<button type="button" class="citation-button" data-source-id="${id}" aria-label="查看来源 ${id}">${match}</button>` : match);
};
export function renderMarkdown(value: string, sources: string[] = []): string {
  if (new TextEncoder().encode(value).length > 65536) throw new Error('ANSWER_TOO_LARGE');
  return DOMPurify.sanitize(md.render(value, { sources }), { USE_PROFILES: { html: true }, ADD_ATTR: ['target', 'rel', 'data-source-id', 'type', 'aria-label'], FORBID_TAGS: ['img', 'svg', 'iframe', 'style', 'form', 'input'], FORBID_ATTR: ['style'] });
}

export function unboundCitations(value: string, sources: string[]): boolean {
  return md.parse(value, {}).some(block => block.children?.some(token => token.type === 'text' && [...token.content.matchAll(/\[(S\d+)\]/g)].some(match => !sources.includes(match[1]!))));
}