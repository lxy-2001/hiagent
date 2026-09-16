package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunSseReadinessTest {
    @Test
    void senderDoesNotReadOrSendUntilMvcSignalsResponseReadiness() throws Exception {
        RunEventHub.Subscription source = mock(RunEventHub.Subscription.class);
        when(source.read(anyLong(), any())).thenReturn(new RunEventHub.ReadResult(RunEventHub.ReadStatus.DONE, null));
        SseEmitter emitter = mock(SseEmitter.class);
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        RunSseSubscription subscription = new RunSseSubscription(source, emitter, 0,
                Clock.fixed(now, ZoneOffset.UTC), now.plusSeconds(60), () -> { });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(subscription);
            verify(source, after(100).never()).read(anyLong(), any());

            subscription.ready();
            future.get();
            verify(source).read(eq(0L), any(Duration.class));
            verify(emitter).complete();
            verify(source).close();
        } finally {
            subscription.signalClose();
            worker.shutdownNow();
        }
    }

    @Test
    void closeBeforeReadyIsIrreversible() throws Exception {
        RunEventHub.Subscription source = mock(RunEventHub.Subscription.class);
        RunSseSubscription subscription = new RunSseSubscription(source, mock(SseEmitter.class), 0,
                Clock.systemUTC(), Instant.now().plusSeconds(60), () -> { });
        subscription.signalClose();
        subscription.ready();
        subscription.run();
        assertThat(subscription.isClosed()).isTrue();
        verify(source, never()).read(anyLong(), any());
        verify(source).close();
    }
}
