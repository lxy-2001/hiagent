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
export function renderMarkdown(value: string): string {
  if (new TextEncoder().encode(value).length > 65536) throw new Error('ANSWER_TOO_LARGE');
  return DOMPurify.sanitize(md.render(value), { USE_PROFILES: { html: true }, ADD_ATTR: ['target', 'rel'], FORBID_TAGS: ['img', 'svg', 'iframe', 'style', 'form', 'input', 'button'], FORBID_ATTR: ['style'] });
}
