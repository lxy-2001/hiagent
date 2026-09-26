import { computed, reactive, ref, shallowRef } from 'vue';
import { sessionsApi } from '../api/sessions';
import { runsApi } from '../api/runs';
import { auth } from './auth';
import { ApiError } from '../api/response';
import { compareDecimal } from '../domain/decimal';
import type { Conversation, ConversationPage, HistoryPage, Turn, AcceptedRun } from '../domain/contracts';
interface Draft { text: string; evidence: boolean; status: 'idle' | 'submitting' | 'uncertain' }
interface WorkspaceApi {
  list(before?: string, signal?: AbortSignal): Promise<ConversationPage>;
  detail(id: string, signal?: AbortSignal): Promise<Conversation>;
  history(id: string, before?: string, until?: string, signal?: AbortSignal): Promise<HistoryPage>;
  submit(input: string, sessionId: string | null, evidence: boolean): Promise<AcceptedRun>;
}
export function createWorkspace(api: WorkspaceApi = { ...sessionsApi, submit: runsApi.submit }) {
  const key = ref('new'), session = shallowRef<Conversation | null>(null), sessions = shallowRef<Conversation[]>([]);
  const turns = shallowRef<Turn[]>([]), accepted = shallowRef<AcceptedRun | null>(null);
  const loading = ref(false), loadingOlder = ref(false), error = ref(''), listError = ref('');
  const drafts = reactive(new Map<string, Draft>());
  const draft = computed(() => { if (!drafts.has(key.value)) drafts.set(key.value, { text: '', evidence: false, status: 'idle' }); return drafts.get(key.value)!; });
  let generation = 0, listGeneration = 0, controller = new AbortController(), chain: HistoryPage | null = null;
  let refreshing: Promise<void> | null = null;
  const hasOlder = ref(false), nextSessions = ref<string | null>(null), listing = ref(false);
  function merge(items: Turn[]) { const map = new Map(turns.value.map(t => [t.runId, t])); for (const item of items) map.set(item.runId, item); turns.value = [...map.values()].sort((a, b) => compareDecimal(a.turnSequence, b.turnSequence)); }
  async function loadSessions(more = false) {
    if (listing.value) return; listing.value = true; const expected = ++listGeneration;
    try { const page = await api.list(more ? nextSessions.value ?? undefined : undefined); if (listGeneration !== expected) return;
      sessions.value = more ? [...new Map([...sessions.value, ...page.items].map(s => [s.sessionId, s])).values()] : page.items;
      nextSessions.value = page.hasMore ? page.nextBefore : null; listError.value = '';
    } catch { if (listGeneration === expected) listError.value = '暂时无法读取会话列表。'; }
    finally { if (listGeneration === expected) listing.value = false; }
  }
  async function select(id: string | null) {
    const expected = ++generation; controller.abort(); controller = new AbortController();
    refreshing = null; key.value = id ?? 'new'; session.value = null; turns.value = []; accepted.value = null; error.value = ''; chain = null; hasOlder.value = false; loadingOlder.value = false;
    loading.value = !!id; if (!id) return;
    try { const [detail, page] = await Promise.all([api.detail(id, controller.signal), api.history(id, undefined, undefined, controller.signal)]);
      if (generation !== expected) return; session.value = detail; turns.value = page.items; chain = page; hasOlder.value = page.hasMore;
    } catch (e) { if (generation === expected) error.value = e instanceof ApiError ? e.message : '无法读取这个会话。'; }
    finally { if (generation === expected) loading.value = false; }
  }
  async function older() {
    if (!session.value || !chain?.hasMore || !chain.nextBeforeSequence || loadingOlder.value) return;
    const expected = generation; loadingOlder.value = true;
    try { const page = await api.history(session.value.sessionId, chain.nextBeforeSequence, chain.untilSequence, controller.signal);
      if (generation !== expected) return; merge(page.items); chain = page; hasOlder.value = page.hasMore;
    } catch (e) { if (generation === expected) error.value = e instanceof ApiError ? e.message : '旧记录读取失败，请重试。'; }
    finally { if (generation === expected) loadingOlder.value = false; }
  }
  function refreshHistory(): Promise<void> {
    if (refreshing) return refreshing;
    if (!session.value) return Promise.resolve();
    const expected = generation, id = session.value.sessionId, signal = controller.signal;
    const knownLatest = turns.value.at(-1)?.turnSequence ?? '0';
    const pending = (async () => {
      try {
        const [latest, detail] = await Promise.all([api.history(id, undefined, undefined, signal), api.detail(id, signal)]);
        if (generation !== expected) return;
        let page = latest;
        const received = [...page.items];
        while (knownLatest && page.hasMore && page.nextBeforeSequence && compareDecimal(page.nextBeforeSequence, knownLatest) > 0) {
          const before = page.nextBeforeSequence;
          page = await api.history(id, before, latest.untilSequence, signal);
          if (generation !== expected) return;
          if (page.untilSequence !== latest.untilSequence || page.hasMore && (!page.nextBeforeSequence || compareDecimal(page.nextBeforeSequence, before) >= 0)) throw new ApiError('INVALID_RESPONSE');
          received.push(...page.items);
        }
        merge(received); session.value = detail; error.value = '';
        if (!chain) { chain = latest; hasOlder.value = latest.hasMore; }
      } catch (e) { if (generation === expected) error.value = e instanceof ApiError ? e.message : '最新记录读取失败。'; }
    })();
    refreshing = pending;
    void pending.finally(() => { if (refreshing === pending) refreshing = null; });
    return pending;
  }
  async function submit(): Promise<AcceptedRun | null> {
    const target = draft.value, id = session.value?.sessionId ?? null, expected = generation;
    if (!target.text.trim() || target.text.length > 8000 || target.status !== 'idle' || session.value?.activeTaskId || loading.value) return null;
    target.status = 'submitting'; error.value = '';
    try { const result = await api.submit(target.text, id, target.evidence); target.text = ''; target.status = 'idle';
      if (generation !== expected) return null; accepted.value = result; await loadSessions(); return result;
    } catch (e) {
      target.status = e instanceof ApiError && e.code !== 'UNKNOWN_SUBMISSION' && e.code !== 'INVALID_RESPONSE' && e.code !== 'HTTP_ERROR' && e.status < 500 ? 'idle' : 'uncertain';
      if (generation === expected) { error.value = e instanceof ApiError ? e.message : '提交结果未确认，请核对会话。'; if (e instanceof ApiError && e.code === 'SESSION_BUSY' && id) await select(id); }
      return null;
    }
  }
  function clear() { refreshing = null; generation++; listGeneration++; controller.abort(); drafts.clear(); key.value = 'new'; session.value = null; turns.value = []; sessions.value = []; accepted.value = null; loading.value = false; listing.value = false; chain = null; hasOlder.value = false; nextSessions.value = null; error.value = ''; listError.value = ''; }
  return { key, session, sessions, turns, draft, accepted, loading, loadingOlder, error, listError, listing, hasOlder, nextSessions,
    select, loadSessions, older, refreshHistory, submit, clear, confirmResubmit() { if (draft.value.status === 'uncertain') draft.value.status = 'idle'; } };
}
export const workspace = createWorkspace();
auth.onReset(workspace.clear);
