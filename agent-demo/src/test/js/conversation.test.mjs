import test from 'node:test';
import assert from 'node:assert/strict';
import {createConversationClient} from '../../main/resources/static/conversation.js';
import {readFile} from 'node:fs/promises';

test('new conversation clears selection; continuation sends its session id', () => {
    const client = createConversationClient(() => { throw Error('unexpected request'); });
    client.select('session');
    assert.deepEqual(client.request('hello'), {input: 'hello', sessionId: 'session'});
    client.reset();
    assert.deepEqual(client.request('new'), {input: 'new'});
});

test('memory writes are explicit, preserve version strings and never retry conflicts', async () => {
    const calls = [];
    const client = createConversationClient(async (url, options) => {
        calls.push({url, options});
        return {ok: false, status: 409, json: async () => ({code: 'MEMORY_VERSION_CONFLICT'})};
    });
    client.select('session');
    assert.equal(calls.length, 0);
    await assert.rejects(client.writeMemory('project_stack', 'Java', '9007199254740993'), /MEMORY_VERSION_CONFLICT/);
    assert.equal(calls.length, 1);
    assert.deepEqual(JSON.parse(calls[0].options.body), {value: 'Java', expectedVersion: '9007199254740993'});
});

test('deletion sends expected version without numeric conversion', async () => {
    const calls = [];
    const client = createConversationClient(async (url, options) => {
        calls.push({url, options});
        return {ok: true, json: async () => ({state: 'DELETED', version: '9007199254740994'})};
    });
    client.select('session');
    const slot = await client.deleteMemory('project_stack', '9007199254740993');
    assert.equal(slot.state, 'DELETED');
    assert.equal(calls[0].url, '/api/agent/sessions/session/memories/project_stack?expectedVersion=9007199254740993');
});

async function pageHarness(fetcher) {
    const elements = new Map();
    function element() {
        return {value: '', textContent: '', disabled: false, children: [], handlers: {},
            addEventListener(name, handler) { this.handlers[name] = handler; },
            replaceChildren(...children) { this.children = children; },
            append(...children) { this.children.push(...children); },
            add(child) { this.children.push(child); },
            set innerHTML(value) { throw Error('unsafe HTML rendering'); }};
    }
    const document = {querySelector(id) {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    }, createElement: element};
    const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
    const script = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1].replace(/^\s*import .*;$/gm, '');
    const observers = [];
    const observeRun = options => { observers.push(options); return {stop() {}, async start() {}}; };
    const page = new Function('document', 'window', 'fetch', 'Option', 'observeRun', 'createConversationClient',
        script + '\nreturn {selectSession, renderSteps};')(document, {addEventListener() {}}, fetcher,
        function Option(text, value) { this.textContent = text; this.value = value; }, observeRun, createConversationClient);
    return {elements, document, observers, ...page};
}

test('changing conversation ignores an older create response and never starts its observer', async () => {
    let resolve;
    const pending = new Promise(done => { resolve = done; });
    const page = await pageHarness(() => pending);
    page.document.querySelector('#taskInput').value = 'first';
    const running = page.document.querySelector('#runBtn').handlers.click();
    page.selectSession(null);
    resolve({ok: true, json: async () => ({taskId: 'old', sessionId: 'old-session'})});
    await running;
    assert.equal(page.observers.length, 0);
    assert.equal(page.document.querySelector('#cancelBtn').disabled, true);
});

test('step diagnostics and untrusted content render as text, without HTML interpretation', async () => {
    const page = await pageHarness(() => { throw Error('unexpected request'); });
    page.renderSteps([{stepNo: 1, stepType: 'CONTEXT_ASSEMBLY', status: 'SUCCESS', output: '<img src=x onerror=alert(1)>'}]);
    const card = page.document.querySelector('#steps').children[0];
    assert.equal(card.children[2].textContent, '<img src=x onerror=alert(1)>');
});
