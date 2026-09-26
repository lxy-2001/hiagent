import { approval, list, object } from '../domain/contracts';
import { http } from './http';
import { runPath } from './runs';
const path = (taskId: string, id?: string) => runPath(taskId) + '/approvals' + (id ? '/' + encodeURIComponent(id) : '');
export const approvalsApi = {
  async list(taskId: string, signal?: AbortSignal) { return list(object(await http.json(path(taskId), { signal })).items, approval, 64); },
  async get(taskId: string, id: string) { return approval(await http.json(path(taskId, id))); },
  async decide(taskId: string, id: string, decision: 'APPROVE' | 'REJECT') { return approval(await http.json(path(taskId, id) + '/decision', { method: 'POST', body: JSON.stringify({ decision }) })); },
};
