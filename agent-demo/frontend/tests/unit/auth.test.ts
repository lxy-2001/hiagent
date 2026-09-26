import { describe, it, expect, vi } from 'vitest';
import { createAuth } from '../../src/state/auth';
import { createHttp } from '../../src/api/http';

const tokens = { accessToken: 'access-one', refreshToken: 'refresh-one', expiresInSeconds: 900 };
const identity = { userId: 'user-one', username: '演示用户', roles: ['USER'] };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
async function setup(extra: (url: string, init?: RequestInit) => Promise<Response>) {
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/auth/login') return json(tokens);
    if (url === '/api/me') return json(identity);
    return extra(url, init);
  });
  const auth = createAuth(fetcher);
  await auth.login('demo', 'password');
  return { auth, http: createHttp(auth, fetcher), fetcher };
}

describe('in-memory authentication boundary', () => {
  it('starts unauthenticated on a new instance and never writes credentials to storage', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const { auth, fetcher } = await setup(async () => json({}));
    expect(auth.user.value?.userId).toBe('user-one');
    expect(createAuth(fetcher).user.value).toBeNull();
    expect(storage).not.toHaveBeenCalled();
  });
  it('shares a single refresh across concurrent reads and retries GET only once', async () => {
    let refreshes = 0;
    const { http } = await setup(async (url, init) => {
      if (url === '/api/auth/refresh') { refreshes++; return json({ ...tokens, accessToken: 'access-two' }); }
      return new Headers(init?.headers).get('Authorization') === 'Bearer access-two' ? json({ ok: true }) : json({}, 401);
    });
    expect(await Promise.all([http.json('/api/one'), http.json('/api/two')])).toEqual([{ ok: true }, { ok: true }]);
    expect(refreshes).toBe(1);
  });
  it('stops after a second 401, clearing identity and owned caches', async () => {
    const { auth, http } = await setup(async url => url === '/api/auth/refresh' ? json(tokens) : json({}, 401));
    const clear = vi.fn(); auth.onReset(clear);
    await expect(http.json('/api/always-denied')).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(auth.user.value).toBeNull();
    expect(clear).toHaveBeenCalledTimes(1);
  });
  it('does not replay mutation requests when authentication fails', async () => {
    const { http, fetcher } = await setup(async () => json({}, 401));
    await expect(http.json('/api/agent/tasks', { method: 'POST', body: '{}' })).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(fetcher.mock.calls.filter(([url]) => url === '/api/agent/tasks')).toHaveLength(1);
    expect(fetcher.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(0);
  });
  it('rejects late responses after logout even when the transport ignores abort', async () => {
    let finish!: (response: Response) => void;
    const { auth, http } = await setup(async url => url === '/api/slow' ? new Promise(resolve => { finish = resolve; }) : json({ success: true }));
    const pending = http.json('/api/slow');
    const rejected = expect(pending).rejects.toMatchObject({ code: 'STALE_REQUEST' });
    await auth.logout(); finish(json({ private: 'old-user' }));
    await rejected;
    expect(auth.user.value).toBeNull();
  });
  it('keeps non-JSON and unknown error bodies out of the displayed error', async () => {
    const { http } = await setup(async () => new Response('<html>private token</html>', { status: 502 }));
    await expect(http.json('/api/broken')).rejects.toMatchObject({ code: 'HTTP_ERROR' });
  });
  it('rejects off-origin URLs before attaching authorization', async () => {
    const { http, fetcher } = await setup(async () => json({}));
    await expect(http.json('https://example.invalid/api/steal')).rejects.toThrow();
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
