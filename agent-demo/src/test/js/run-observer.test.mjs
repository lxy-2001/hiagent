import test from "node:test";
import assert from "node:assert/strict";
import {observeRun} from "../../main/resources/static/run-events.js";

const terminal = {taskId: "task", status: "SUCCEEDED", finalAnswer: "done"};

test("uses Authorization and bounded 1/2/4 second retries before polling final state", async () => {
    const calls = [];
    const sleeps = [];
    const responses = [new Response("", {status: 503}), streamResponse(), streamResponse(),
        new Response("", {status: 410}), new Response(JSON.stringify(terminal), {status: 200}),
        new Response("[]", {status: 200})];
    const observer = observeRun({taskId: "task", token: "secret", generation: 1,
        fetchImpl: async (url, init) => { calls.push({url, init}); return responses.shift(); },
        sleep: async milliseconds => sleeps.push(milliseconds), isCurrent: () => true});
    await observer.start();
    assert.deepEqual(sleeps.slice(0, 3), [1000, 2000, 4000]);
    assert.ok(calls.every(call => call.init.headers.Authorization === "Bearer secret"));
    assert.ok(calls.every(call => !call.url.includes("secret")));
    assert.ok(calls.every(call => call.init.method !== "POST"));
});

test("401 stops immediately and a stale generation cannot update UI", async () => {
    let updates = 0;
    const observer = observeRun({taskId: "task", token: "secret", generation: 1,
        fetchImpl: async () => new Response("", {status: 401}), sleep: async () => {},
        isCurrent: () => false, onEvent: () => updates++, onSnapshot: () => updates++});
    await observer.start();
    assert.equal(updates, 0);
});

test("410 performs serial bounded GETs and loads steps once after terminal", async () => {
    const calls = [];
    const responses = [new Response("", {status: 410}),
        new Response(JSON.stringify({taskId: "task", status: "RUNNING"}), {status: 200}),
        new Response(JSON.stringify(terminal), {status: 200}), new Response("[]", {status: 200})];
    let snapshots = 0;
    let steps = 0;
    const observer = observeRun({taskId: "task", token: "secret", generation: 1,
        fetchImpl: async (url, init) => { calls.push({url, init}); return responses.shift(); },
        sleep: async () => {}, isCurrent: () => true,
        onSnapshot: () => snapshots++, onSteps: () => steps++});
    await observer.start();
    assert.equal(snapshots, 2);
    assert.equal(steps, 1);
    assert.equal(calls.filter(call => call.url.endsWith("/steps")).length, 1);
});

function streamResponse() {
    return new Response(new ReadableStream({start(controller) { controller.close(); }}),
        {status: 200, headers: {"Content-Type": "text/event-stream"}});
}

for (const status of [204, 410]) {
    test('unknown event survives reconnect and ' + status + ' refreshes approvals', async () => {
        const event = {eventId:'9',taskId:'task',runId:'task',type:'FUTURE_EVENT',occurredAt:'2026-09-25T00:00:00Z',payload:{}};
        const responses = [new Response('id: 9\nevent: FUTURE_EVENT\ndata: '+JSON.stringify(event)+'\n\n', {status:200}),
            new Response(null,{status}),new Response(JSON.stringify(terminal)),new Response('[]')];
        const calls=[]; let recovered=0; let displayed=0;
        const observer=observeRun({taskId:'task',token:'fixture',generation:1,isCurrent:()=>true,
            fetchImpl:async (url,init)=>{calls.push({url,init});return responses.shift();},sleep:async()=>{},
            onEvent:()=>displayed++,onRecovery:()=>recovered++});
        await observer.start();
        assert.equal(calls[1].init.headers['Last-Event-ID'],'9');
        assert.equal(observer.lastEventId(),'9');
        assert.equal(displayed,0);
        assert.equal(recovered,1);
    });
}

test('closing observation aborts its request and prevents reconnect', async () => {
    let signal; let calls = 0;
    const observer = observeRun({taskId:'task',token:'fixture',generation:1,isCurrent:()=>true,
        fetchImpl: async (url, init) => {
            calls++; signal = init.signal;
            return new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')), {once:true}));
        }});
    const running = observer.start();
    observer.stop();
    await running;
    assert.equal(signal.aborted, true);
    assert.equal(calls, 1);
});
