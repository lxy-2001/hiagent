import { describe, it, expect } from 'vitest';
import { decimal, compareDecimal } from '../../src/domain/decimal';
import { conversation, historyPage, uiContext, runSnapshot } from '../../src/domain/contracts';

describe('decimal API identities', () => {
  it('preserves values beyond JavaScript safe integers', () => {
    expect(decimal('9007199254740993')).toBe('9007199254740993');
    expect(compareDecimal('9007199254740993', '9007199254740992')).toBe(1);
  });
  it('rejects coercions, leading zeros, negatives and long overflow', () => {
    for (const value of [1, '-1', '01', '1.0', '9223372036854775808']) expect(() => decimal(value)).toThrow();
  });
});

describe('server response boundaries', () => {
  const session = { sessionId: 'session-1', title: 'Topic', createdAt: '2026-09-26T00:00:00Z', lastTurnSequence: '2', activeTaskId: null };
  it('accepts compatible extra fields and empty history', () => {
    expect(conversation({ ...session, future: true }).title).toBe('Topic');
    expect(historyPage({ items: [], hasMore: false, nextBeforeSequence: null, untilSequence: '0' }).items).toEqual([]);
  });
  it('rejects malformed shapes, oversized pages and numeric sequence fields', () => {
    expect(() => conversation({ ...session, lastTurnSequence: 2 })).toThrow();
    expect(() => historyPage({ items: new Array(51).fill(null), hasMore: true, untilSequence: '1' })).toThrow();
    expect(() => conversation('<html>failure</html>')).toThrow();
    expect(() => conversation(null)).toThrow();
  });
  it('never invents a valid mode or terminal status', () => {
    expect(uiContext({ mode: 'OFFLINE_FIXTURE', modelLabel: null, ragEnabled: true, mcpEnabled: false }).mode).toBe('OFFLINE_FIXTURE');
    expect(() => uiContext({ mode: 'CONNECTED', ragEnabled: true, mcpEnabled: false })).toThrow();
    expect(() => runSnapshot({ status: 'DONE' })).toThrow();
  });
});
