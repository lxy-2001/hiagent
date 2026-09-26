import { auth as defaultAuth, type Auth } from '../state/auth';
import { ApiError, decode, type Fetcher } from './response';
export function createHttp(auth: Auth, fetcher: Fetcher = (url, init) => fetch(url, init)) {
  async function response(path: string, options: RequestInit = {}): Promise<Response> {
    if (!path.startsWith('/api/') || path.includes('\\') || path.includes('#')) throw new ApiError('INVALID_REQUEST');
    const expected = auth.generation;
    const method = (options.method ?? 'GET').toUpperCase();
    const safe = method === 'GET';
    const signal = options.signal ? AbortSignal.any([options.signal, auth.signal]) : auth.signal;
    async function send(retried: boolean): Promise<Response> {
      auth.current(expected);
      const token = auth.accessToken;
      if (!token) throw new ApiError('UNAUTHORIZED');
      const headers = new Headers(options.headers);
      headers.set('Authorization', `Bearer ${token}`);
      if (options.body) headers.set('Content-Type', 'application/json');
      let result: Response;
      try { result = await fetcher(path, { ...options, method, headers, signal, credentials: 'omit', cache: 'no-store', redirect: 'error' }); }
      catch { auth.current(expected); throw new ApiError(signal.aborted ? 'REQUEST_ABORTED' : safe ? 'NETWORK_ERROR' : 'UNKNOWN_SUBMISSION'); }
      auth.current(expected);
      if (result.status === 401) {
        if (safe && !retried) { await auth.refresh(expected, token); return send(true); }
        auth.reset(); throw new ApiError('UNAUTHORIZED', 401);
      }
      return result;
    }
    return send(false);
  }
  async function json(path: string, options: RequestInit = {}): Promise<unknown> {
    const expected = auth.generation;
    const result = await response(path, options);
    const value = await decode(result); auth.current(expected); return value;
  }
  return { response, json };
}
export const http = createHttp(defaultAuth);
