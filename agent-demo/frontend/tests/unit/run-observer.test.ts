import { describe, it, expect, vi } from 'vitest';
import { createSseParser } from '../../src/composables/sse-parser';
import { observeRun } from '../../src/composables/useRunObserver';
const enc = new TextEncoder();
function frame(id: string, type = 'AGENT_STEP') { return `id: ${id}\nevent: ${type}\ndata: ${JSON.stringify({ eventId: id, taskId: 'task', runId: 'task', type, occurredAt: '2026-09-26T00:00:00Z', payload: { summary: '你好' } })}\n\n`; }
describe('bounded SSE parser and observer', () => {
  it('handles split UTF8, comments, unknown and duplicate events with precise cursor', async () => {
    const received = vi.fn(); const p = createSseParser({ taskId: 'task', onEvent: received });
    const bytes = enc.encode(': ping\n\n' + frame('9007199254740993') + frame('9007199254740993') + frame('9007199254740994', 'FUTURE_EVENT'));
    for (const byte of bytes) await p.push(new Uint8Array([byte]));
    expect(received).toHaveBeenCalledTimes(1); expect(p.lastEventId()).toBe('9007199254740994');
  });
  it('rejects invalid envelopes and byte overflow without advancing cursor', async () => {
    const p = createSseParser({ taskId: 'other', onEvent: vi.fn() });
    await expect(p.push(enc.encode(frame('1')))).rejects.toThrow(); expect(p.lastEventId()).toBe('0');
    await expect(createSseParser({ taskId: 'task', onEvent: vi.fn() }).push(enc.encode('x'.repeat(32769)))).rejects.toThrow();
  });
  it.each([204, 410])('uses snapshot and steps after %s without writes', async status => {
    const get = vi.fn(async (url: string) => url.endsWith('/events') ? new Response(null, { status }) : new Response(JSON.stringify(url.endsWith('/steps') ? [] : { status: 'SUCCEEDED' })));
    const snapshot = vi.fn(); const state = vi.fn();
    const observer = observeRun({ taskId: 'task', get, onSnapshot: snapshot, onSteps: vi.fn(), onState: state, sleep: async () => undefined });
    await observer.start(); expect(snapshot).toHaveBeenCalledTimes(1); expect(get).toHaveBeenCalledTimes(3); expect(state).toHaveBeenLastCalledWith('closed');
  });
  it('stops immediately on unauthorized and stopping discards late reads', async () => {
    const state = vi.fn(); const observer = observeRun({ taskId: 'task', get: async () => new Response(null, { status: 401 }), onSnapshot: vi.fn(), onSteps: vi.fn(), onState: state });
    await observer.start(); expect(state).toHaveBeenLastCalledWith('unauthorized');
    let finish!: (value: Response) => void; const snapshot = vi.fn();
    const late = observeRun({ taskId: 'task', get: () => new Promise(resolve => { finish = resolve; }), onSnapshot: snapshot, onSteps: vi.fn(), onState: vi.fn() });
    const pending = late.start(); late.stop(); finish(new Response(null, { status: 204 })); await pending;
    expect(snapshot).not.toHaveBeenCalled();
  });
});
