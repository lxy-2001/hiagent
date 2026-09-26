import { decimal } from '../domain/decimal';
import { object, text } from '../domain/contracts';
export interface RunEvent { eventId: string; taskId: string; runId: string; type: string; occurredAt: string; payload: unknown }
const known = new Set(['RUN_CREATED', 'RUN_STARTED', 'AGENT_STEP', 'APPROVAL_REQUESTED', 'APPROVAL_RESOLVED', 'RUN_TERMINATED']);
export function createSseParser(options: { taskId: string; onEvent: (event: RunEvent) => unknown; initialEventId?: string; maximumFrameBytes?: number }) {
  let pending = new Uint8Array(0), last = decimal(options.initialEventId ?? '0');
  const decoder = new TextDecoder('utf-8', { fatal: true }), maximum = options.maximumFrameBytes ?? 32768;
  async function push(chunk: Uint8Array) {
    // Bound the retained frame while accepting transports that coalesce many frames.
    for (let offset = 0; offset < chunk.length; offset += 4096) {
      const block = chunk.slice(offset, offset + 4096), joined = new Uint8Array(pending.length + block.length);
      joined.set(pending); joined.set(block, pending.length); pending = joined;
      for (;;) {
        let index = -1, length = 0;
        for (let i = 0; i + 1 < pending.length; i++) {
          if (pending[i] === 10 && pending[i + 1] === 10) { index = i; length = 2; break; }
          if (pending[i] === 13 && pending[i + 1] === 10 && pending[i + 2] === 13 && pending[i + 3] === 10) { index = i; length = 4; break; }
        }
        if (index < 0) break;
        if (index > maximum) throw new Error('SSE_FRAME_TOO_LARGE');
        const raw = decoder.decode(pending.slice(0, index)); pending = pending.slice(index + length);
        let id: string | null = null, type = 'message'; const data: string[] = [];
        for (const line of raw.replaceAll('\r\n', '\n').split('\n')) {
          if (line.startsWith(':')) continue; const colon = line.indexOf(':');
          const field = colon < 0 ? line : line.slice(0, colon), value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
          if (field === 'id') id = value; else if (field === 'event') type = value; else if (field === 'data') data.push(value);
        }
        if (id === null && data.length === 0) continue;
        const sequence = decimal(id); if (sequence === '0') throw new Error('INVALID_EVENT_ID');
        const value = object(JSON.parse(data.join('\n')));
        if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(type) || value.type !== type || value.eventId !== id || value.taskId !== options.taskId || value.runId !== options.taskId || !('payload' in value)) throw new Error('INVALID_EVENT_ENVELOPE');
        const event: RunEvent = { eventId: sequence, type, taskId: options.taskId, runId: options.taskId, occurredAt: text(value.occurredAt, 128), payload: value.payload };
        if (BigInt(sequence) <= BigInt(last)) continue;
        if (known.has(type)) await options.onEvent(event);
        last = sequence;
      }
      if (pending.length > maximum + 3) throw new Error('SSE_FRAME_TOO_LARGE');
    }
    if (pending.length > maximum) throw new Error('SSE_FRAME_TOO_LARGE');
  }
  return { push, finish() { pending = new Uint8Array(0); }, lastEventId: () => last };
}
