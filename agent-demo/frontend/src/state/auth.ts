import { shallowRef, readonly } from 'vue';
import { object, text, integer, list } from '../domain/contracts';
import { ApiError, decode, type Fetcher } from '../api/response';
export interface User { userId: string; username: string; roles: string[] }
interface Tokens { accessToken: string; refreshToken: string; expiresInSeconds: number }
function parseTokens(value: unknown): Tokens {
  const v = object(value);
  return { accessToken: text(v.accessToken, 16384), refreshToken: text(v.refreshToken, 16384), expiresInSeconds: integer(v.expiresInSeconds) };
}
export function createAuth(fetcher: Fetcher = (url, init) => fetch(url, init)) {
  const user = shallowRef<User | null>(null);
  let tokens: Tokens | null = null;
  let generation = 0;
  let controller = new AbortController();
  let refreshing: Promise<void> | null = null;
  const resetListeners = new Set<() => void>();
  function reset() {
    generation++; controller.abort(); controller = new AbortController();
    tokens = null; user.value = null; refreshing = null;
    for (const listener of resetListeners) listener();
  }
  function current(expected: number) { if (generation !== expected) throw new ApiError('STALE_REQUEST'); }
  async function raw(path: string, body: unknown, expected: number, access?: string) {
    const response = await fetcher(path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(access ? { Authorization: `Bearer ${access}` } : {}) }, body: JSON.stringify(body), signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]), cache: 'no-store', credentials: 'omit', redirect: 'error' });
    current(expected);
    const value = await decode(response); current(expected); return value;
  }
  async function login(username: string, password: string) {
    reset(); const expected = generation;
    try {
      const issued = parseTokens(await raw('/api/auth/login', { username, password }, expected));
      const response = await fetcher('/api/me', { headers: { Authorization: `Bearer ${issued.accessToken}` }, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]), cache: 'no-store', credentials: 'omit', redirect: 'error' });
      current(expected); const me = object(await decode(response)); current(expected);
      tokens = issued;
      user.value = { userId: text(me.userId, 128), username: text(me.username, 128), roles: list(me.roles, v => text(v, 64), 32) };
    } catch (error) { if (generation === expected) reset(); throw error instanceof ApiError ? error : new ApiError('NETWORK_ERROR'); }
  }
  async function refresh(expected: number, rejectedToken: string | null) {
    current(expected);
    if (!tokens) throw new ApiError('UNAUTHORIZED');
    if (tokens.accessToken !== rejectedToken) return;
    if (!refreshing) {
      const pending = (async () => {
        try { const value = await raw('/api/auth/refresh', { refreshToken: tokens!.refreshToken }, expected); current(expected); tokens = parseTokens(value); }
        catch (error) { if (generation === expected) reset(); throw error instanceof ApiError ? error : new ApiError('NETWORK_ERROR'); }
      })();
      refreshing = pending;
      void pending.finally(() => { if (refreshing === pending) refreshing = null; }).catch(() => undefined);
    }
    await refreshing;
  }
  async function logout(): Promise<boolean> {
    const previous = tokens; reset();
    if (!previous) return true;
    try {
      const response = await fetcher('/api/auth/logout', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${previous.accessToken}` }, body: JSON.stringify({ refreshToken: previous.refreshToken }), signal: AbortSignal.timeout(5000), cache: 'no-store', credentials: 'omit', redirect: 'error' });
      return response.ok;
    } catch { return false; }
  }
  return { user: readonly(user), login, logout, reset, refresh, current,
    get generation() { return generation; }, get signal() { return controller.signal; },
    get accessToken() { return tokens?.accessToken ?? null; },
    onReset(listener: () => void) { resetListeners.add(listener); return () => resetListeners.delete(listener); } };
}
export type Auth = ReturnType<typeof createAuth>;
export const auth = createAuth();
