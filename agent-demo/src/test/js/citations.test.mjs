import test from 'node:test';
import assert from 'node:assert/strict';
import {createConversationClient} from '../../main/resources/static/conversation.js';
import {createApprovalClient, renderApprovals} from '../../main/resources/static/approval.js';
import {readFile} from 'node:fs/promises';

test('evidence requirement is explicit and preserved on continuation', () => {
    const client = createConversationClient(() => { throw Error('unexpected IO'); });
    assert.deepEqual(client.request('q', true), {input: 'q', requireEvidence: true});
    client.select('session');
    assert.deepEqual(client.request('q', true), {input: 'q', sessionId: 'session', requireEvidence: true});
    assert.deepEqual(client.request('q'), {input: 'q', sessionId: 'session'});
});

test('page renders persisted citations as text and guards stale snapshot callbacks', async () => {
    const elements = new Map();
    const element = () => ({value: '', checked: false, textContent: '', children: [], handlers: {},
        addEventListener(name, fn) { this.handlers[name] = fn; },
        replaceChildren(...children) { this.children = children; }, append(...children) { this.children.push(...children); },
        add(child) { this.children.push(child); }, set innerHTML(value) { throw Error('unsafe HTML'); }});
    const document = {querySelector(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); }, createElement: element};
    const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
    const script = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1].replace(/^\s*import .*;$/gm, '');
    let observer;
    const page = new Function('document', 'window', 'fetch', 'Option', 'observeRun', 'createConversationClient', 'createApprovalClient', 'renderApprovals', script + '\nreturn {renderSnapshot,selectSession};')(
        document, {addEventListener() {}}, async () => ({ok: true, json: async () => ({taskId: 'r',sessionId: 's',items:[]})}),
        function() {}, options => { observer = options; return {stop(){},async start(){}}; }, createConversationClient, createApprovalClient, renderApprovals);
    page.renderSnapshot({taskId: 'r',status:'SUCCEEDED', citations:[{id:'S1',sourcePath:'https://untrusted',title:'<img>',start:0,end:6,excerpt:'<evil>'}]});
    const card = document.querySelector('#citations').children[0];
    assert.ok(card);
    assert.ok(card.children.some(child => child.textContent.includes('<evil>')));
    await document.querySelector('#runBtn').handlers.click();
    page.selectSession(null);
    observer.onSnapshot({taskId:'r',status:'SUCCEEDED',finalAnswer:'stale answer',citations:[]});
    assert.ok(!document.querySelector('#output').textContent.includes('stale answer'));
});
