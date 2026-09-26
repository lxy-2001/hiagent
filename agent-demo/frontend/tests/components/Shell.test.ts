import { describe, it, expect } from 'vitest';
import { safeReturn } from '../../src/router';
import { preferences } from '../../src/state/preferences';
describe('shell navigation and preferences', () => {
  it('accepts only valid internal return destinations', () => {
    expect(safeReturn('/sessions/abc-123')).toBe('/sessions/abc-123');
    for (const unsafe of ['//outside.test', 'https://outside.test', '/login', '/sessions/a/b', '/new?token=private', '/nope']) expect(safeReturn(unsafe)).toBe('/new');
  });
  it('stores only an allowlisted theme preference', () => {
    preferences.setTheme('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('hiagent.theme')).toBe('dark');
    preferences.setTheme('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });
});
