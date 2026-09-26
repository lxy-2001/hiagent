import { memorySlot, list, type MemorySlot } from '../domain/contracts';
import { http } from './http';
const path = (sessionId: string, key?: MemorySlot['key']) => `/api/agent/sessions/${encodeURIComponent(sessionId)}/memories` + (key ? '/' + key : '');
export const memoriesApi = {
  async get(id: string, signal?: AbortSignal) { return list(await http.json(path(id), { signal }), memorySlot, 2); },
  async put(id: string, key: MemorySlot['key'], value: string, expectedVersion: string) { return memorySlot(await http.json(path(id, key), { method: 'PUT', body: JSON.stringify({ value, expectedVersion }) })); },
  async remove(id: string, key: MemorySlot['key'], expectedVersion: string) { return memorySlot(await http.json(path(id, key) + '?expectedVersion=' + encodeURIComponent(expectedVersion), { method: 'DELETE' })); },
};
