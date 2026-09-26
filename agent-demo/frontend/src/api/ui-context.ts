import { uiContext } from '../domain/contracts';
import { http } from './http';
export async function getUiContext(signal?: AbortSignal) { return uiContext(await http.json('/api/demo/ui-context', { signal })); }
