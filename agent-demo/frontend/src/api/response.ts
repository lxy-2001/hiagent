import { errorMessage } from '../domain/error-message';
export type Fetcher = (url: string, init?: RequestInit) => Promise<Response>;
export class ApiError extends Error {
  constructor(readonly code: string, readonly status = 0) { super(errorMessage(code)); }
}
export async function decode(response: Response): Promise<unknown> {
  let value: unknown;
  try { value = await response.json(); } catch { throw new ApiError(response.ok ? 'INVALID_RESPONSE' : 'HTTP_ERROR', response.status); }
  if (!response.ok) {
    const code = value && typeof value === 'object' && 'code' in value && typeof value.code === 'string' && /^[A-Z_]{1,64}$/.test(value.code) ? value.code : 'HTTP_ERROR';
    throw new ApiError(response.status === 401 ? 'UNAUTHORIZED' : code, response.status);
  }
  return value;
}
