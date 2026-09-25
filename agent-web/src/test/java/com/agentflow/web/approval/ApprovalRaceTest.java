package com.agentflow.web.approval;

import com.agentflow.core.approval.*;
import com.agentflow.core.runtime.*;
import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalRaceTest {
    @Test void cancellationWhileDispatchCommitReturnsWinsBeforePermit() throws Exception {
        var request = ApprovalFixtures.request(); var row = ToolInvocationEntity.pending(request, "owner");
        var persistence = mock(ApprovalPersistence.class);
        when(persistence.create(request)).thenAnswer(i -> row.snapshot());
        when(persistence.getOwned(anyString(), anyString(), anyString())).thenAnswer(i -> row.snapshot());
        var run = ApprovalFixtures.control(); var budget = new ToolExecutionControl(run, TimeSource.system(), Duration.ofSeconds(10));
        var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), System::nanoTime);
        service.begin(request, run, budget, event -> {});
        row.resolve(ApprovalStatus.APPROVED, ApprovalResolution.DecisionSource.USER, ApprovalFixtures.NOW, 1);
        var committed = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(persistence.dispatch(eq(request), any())).thenAnswer(i -> { row.markDispatch(ApprovalFixtures.NOW); committed.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue(); return row.snapshot(); });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var dispatch = executor.submit(() -> service.dispatch(request, budget));
            assertThat(committed.await(2, TimeUnit.SECONDS)).isTrue();
            run.requestCancel(); release.countDown();
            assertThat(dispatch.get(2, TimeUnit.SECONDS)).isFalse();
            assertThat(row.snapshot().status()).isEqualTo(ApprovalStatus.APPROVED);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void monotonicTtlExpiresDespiteFrozenWallClockAndCancellationHasPriority() {
        for (boolean cancelled : new boolean[]{false, true}) {
            var tick = new AtomicLong(); var request = ApprovalFixtures.request();
            var row = ToolInvocationEntity.pending(request, "owner"); var persistence = mock(ApprovalPersistence.class);
            when(persistence.create(request)).thenAnswer(i -> row.snapshot());
            when(persistence.getOwned(anyString(), anyString(), anyString())).thenAnswer(i -> row.snapshot());
            when(persistence.resolve(anyString(), anyString(), anyString(), any(), any(), any(), anyLong())).thenAnswer(i -> {
                row.resolve(i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6)); return row.snapshot(); });
            var run = ApprovalFixtures.control();
            var service = new ApprovalService(persistence, Clock.fixed(ApprovalFixtures.NOW, ZoneOffset.UTC), tick::get);
            service.begin(request, run, new ToolExecutionControl(run, tick::get, Duration.ofSeconds(1)), event -> {});
            tick.set(Duration.ofSeconds(1).toNanos()); if (cancelled) run.requestCancel();
            var resolved = service.poll("owner", ApprovalFixtures.RUN, request.approvalId().toString());
            assertThat(resolved.status()).isEqualTo(cancelled ? ApprovalStatus.CANCELLED : ApprovalStatus.EXPIRED);
            assertThat(resolved.waitMillis()).isEqualTo(1000);
            assertThatThrownBy(() -> service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE))
                    .isInstanceOf(ApprovalService.ConflictException.class);
        }
    }
}
