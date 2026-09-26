import { it, expect, vi } from 'vitest';
import { useActiveRun } from '../../src/composables/useActiveRun';
import { runsApi } from '../../src/api/runs';
import { ApiError } from '../../src/api/response';
import type { RunSnapshot } from '../../src/domain/contracts';
it('recovers a transient first snapshot 503 without submitting a new run', async () => {
  vi.useFakeTimers();
  try {
    const snapshot: RunSnapshot = { taskId: 'r', runId: 'r', sessionId: 's', status: 'SUCCEEDED', input: 'q', finalAnswer: 'a', createdAt: '', updatedAt: '', startedAt: null, finishedAt: null, cancelRequested: false, terminationReason: 'COMPLETED', runtimeReason: null, errorCode: null, recordingComplete: true, usage: null, requireEvidence: false, citations: [] };
    const read = vi.spyOn(runsApi, 'snapshot').mockRejectedValueOnce(new ApiError('PERSISTENCE_UNAVAILABLE', 503)).mockResolvedValue(snapshot);
    vi.spyOn(runsApi, 'steps').mockResolvedValue([]); const submit = vi.spyOn(runsApi, 'submit');
    const run = useActiveRun(async () => undefined); const pending = run.open('r'); await vi.runAllTimersAsync(); await pending;
    expect(read).toHaveBeenCalledTimes(2); expect(run.snapshot.value?.status).toBe('SUCCEEDED'); expect(submit).not.toHaveBeenCalled(); run.stop();
  } finally { vi.useRealTimers(); }
});
