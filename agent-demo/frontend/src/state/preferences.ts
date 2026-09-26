import { ref, readonly } from 'vue';
export type Theme = 'light' | 'dark' | 'system';
const media = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)') : null;
function stored(): Theme { try { const value = localStorage.getItem('hiagent.theme'); return value === 'dark' || value === 'light' ? value : 'system'; } catch { return 'system'; } }
const theme = ref<Theme>(stored());
function apply() { document.documentElement.dataset.theme = theme.value === 'system' ? media?.matches ? 'dark' : 'light' : theme.value; }
media?.addEventListener('change', apply);
export const preferences = { theme: readonly(theme), setTheme(value: Theme) { theme.value = value; try { localStorage.setItem('hiagent.theme', value); } catch { /* Private mode still supports the current theme. */ } apply(); } };
apply();
