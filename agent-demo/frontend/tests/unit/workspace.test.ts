import { describe, it, expect, vi } from 'vitest';
import { createWorkspace } from '../../src/state/workspace';
import { ApiError } from '../../src/api/response';
const detail = (id: string) => ({ sessionId: id, title: id, createdAt: '2026-09-26T00:00:00Z', lastTurnSequence: '0', activeTaskId: null });
const empty: import('../../src/domain/contracts').HistoryPage = { items: [], hasMore: false, nextBeforeSequence: null, untilSequence: '0' };
function api() { return { list: vi.fn(async () => ({ items: [], hasMore: false, nextBefore: null })), detail: vi.fn(async (id: string) => detail(id)), history: vi.fn(async () => empty), submit: vi.fn(async () => ({ taskId: 'r1', runId: 'r1', sessionId: 's1', status: 'QUEUED' as const })) }; }
describe('workspace ownership and submission', () => {
  it('creates a session only after acceptance and sends at most once on double click', async () => {
    const service = api(), state = createWorkspace(service);
    state.draft.value.text = 'hello';
    expect(state.session.value).toBeNull();
    await Promise.all([state.submit(), state.submit()]);
    expect(service.submit).toHaveBeenCalledTimes(1);
    expect(state.accepted.value?.sessionId).toBe('s1');
    expect(state.draft.value.text).toBe('');
  });
  it('locks an ambiguous write without automatic replay, preserving draft', async () => {
    const service = api(); service.submit.mockRejectedValue(new ApiError('UNKNOWN_SUBMISSION'));
    const state = createWorkspace(service); state.draft.value.text = 'hello';
    await state.submit(); await state.submit();
    expect(state.draft.value.status).toBe('uncertain'); expect(state.draft.value.text).toBe('hello');
    expect(service.submit).toHaveBeenCalledTimes(1);
  });
  it('isolates late selection responses and keeps drafts only in current identity memory', async () => {
    const service = api(); let release!: (value: ReturnType<typeof detail>) => void;
    service.detail.mockImplementation(async id => id === 'old' ? new Promise(resolve => { release = resolve; }) : detail(id));
    const state = createWorkspace(service); state.draft.value.text = 'unsent';
    const old = state.select('old'); await state.select('new'); release(detail('old')); await old;
    expect(state.session.value?.sessionId).toBe('new');
    await state.select(null); expect(state.draft.value.text).toBe('unsent');
    state.clear(); expect(state.draft.value.text).toBe(''); expect(state.session.value).toBeNull();
  });
  it('rejects whitespace and text beyond the UTF16 input limit before HTTP', async () => {
    const service = api(), state = createWorkspace(service);
    for (const text of [' ', 'x'.repeat(8001)]) { state.draft.value.text = text; await state.submit(); }
    expect(service.submit).not.toHaveBeenCalled();
  });
});

it('keeps the original pagination watermark while merging a newer completed turn', async () => {
  const make = (sequence: string): import('../../src/domain/contracts').Turn => ({ runId: 'run-' + sequence, sessionId: 's', turnSequence: sequence, status: 'SUCCEEDED', input: 'q', finalAnswer: 'a', terminationReason: 'COMPLETED', recordingComplete: true, contentUnavailable: false, createdAt: '2026-09-26T00:00:00Z', finishedAt: '2026-09-26T00:00:01Z' });
  const history = vi.fn(async (_id: string, before?: string): Promise<import('../../src/domain/contracts').HistoryPage> => before ? { items: [make('1'), make('2')], hasMore: false, nextBeforeSequence: null, untilSequence: '4' } : { items: [make('3'), make('4')], hasMore: true, nextBeforeSequence: '3', untilSequence: '4' });
  const service = { ...api(), history }, state = createWorkspace(service);
  await state.select('s');
  history.mockResolvedValueOnce({ items: [make('4'), make('5')], hasMore: true, nextBeforeSequence: '4', untilSequence: '5' });
  await state.refreshHistory(); await state.older();
  expect(history.mock.calls.at(-1)?.slice(0, 3)).toEqual(['s', '3', '4']);
  expect(state.turns.value.map(t => t.turnSequence)).toEqual(['1', '2', '3', '4', '5']);
});

it('backfills a gap using the new watermark without moving the older history chain', async () => {
  const make = (n: number): import('../../src/domain/contracts').Turn => ({ runId: 'r' + n, sessionId: 's', turnSequence: String(n), status: 'SUCCEEDED', input: 'q', finalAnswer: 'a', terminationReason: 'COMPLETED', recordingComplete: true, contentUnavailable: false, createdAt: '2026-09-26T00:00:00Z', finishedAt: '2026-09-26T00:00:01Z' });
  const page = (a: number, b: number, until: string) => ({ items: Array.from({ length: b-a+1 }, (_, i) => make(a+i)), hasMore: a > 1, nextBeforeSequence: a > 1 ? String(a) : null, untilSequence: until });
  const history = vi.fn(async (_id: string, before?: string, until?: string) => before === '81' ? page(61,80,until!) : before === '61' ? page(41,60,until!) : before === '41' ? page(21,40,until!) : before === '21' ? page(1,20,until!) : page(21,40,'40'));
  const state = createWorkspace({ ...api(), history }); await state.select('s');
  history.mockResolvedValueOnce(page(81,100,'100'));
  await Promise.all([state.refreshHistory(), state.refreshHistory()]);
  expect(state.turns.value.map(t => t.turnSequence)).toEqual(Array.from({length:80}, (_,i) => String(i+21)));
  expect(history.mock.calls.filter(([, before]) => !before)).toHaveLength(2);
  expect(history.mock.calls.slice(2).every(([, , until]) => until === '100')).toBe(true);
  await state.older(); expect(history.mock.calls.at(-1)).toEqual(['s','21','40',expect.any(AbortSignal)]);
});
it('fills new history after a previously empty session', async () => {
  const service = api();
  const state = createWorkspace(service); await state.select('s');
  const turn = (n: string): import('../../src/domain/contracts').Turn => ({ runId: n, sessionId: 's', turnSequence: n, status: 'SUCCEEDED', input: 'q', finalAnswer: 'a', terminationReason: 'COMPLETED', recordingComplete: true, contentUnavailable: false, createdAt: '', finishedAt: '' });
  service.history.mockResolvedValueOnce({ items: [turn('2')], hasMore: true, nextBeforeSequence: '2', untilSequence: '2' });
  service.history.mockResolvedValueOnce({ items: [turn('1')], hasMore: false, nextBeforeSequence: null, untilSequence: '2' });
  await state.refreshHistory(); expect(state.turns.value.map(t => t.turnSequence)).toEqual(['1','2']);
});
it('rejects non-decreasing backfill cursors without partially merging new history', async () => {
  const service = api(), state = createWorkspace(service); await state.select('s');
  service.history.mockResolvedValue({ items: [], hasMore: true, nextBeforeSequence: '9', untilSequence: '10' });
  await state.refreshHistory(); expect(state.error.value).toBeTruthy();
  expect(service.history).toHaveBeenCalledTimes(3); expect(state.turns.value).toEqual([]);
});
