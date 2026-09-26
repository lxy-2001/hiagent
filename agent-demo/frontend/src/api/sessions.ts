import { conversation, conversationPage, historyPage } from '../domain/contracts';
import { http } from './http';
const path = (id: string) => `/api/agent/sessions/${encodeURIComponent(id)}`;
export const sessionsApi = {
  async list(before?: string, signal?: AbortSignal) { return conversationPage(await http.json('/api/agent/sessions' + (before ? '?before=' + encodeURIComponent(before) : ''), { signal })); },
  async detail(id: string, signal?: AbortSignal) { return conversation(await http.json(path(id), { signal })); },
  async history(id: string, before?: string, until?: string, signal?: AbortSignal) {
    const query = new URLSearchParams({ limit: '20' }); if (before) query.set('beforeSequence', before); if (until) query.set('untilSequence', until);
    return historyPage(await http.json(path(id) + '/history?' + query, { signal }));
  },
};
