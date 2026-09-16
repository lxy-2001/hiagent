package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RunSseSendLoopTest {
    @Test
    void sendsOneTerminalFrameThenCompletesAndReleasesSubscription() throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        RunEvent event = new RunEvent("1", "task", "task", RunEvent.Type.RUN_TERMINATED,
                now, Map.of("status", "SUCCEEDED"));
        RunEventHub.PublishedFrame frame = new RunEventHub.PublishedFrame(event,
                "{\"eventId\":\"1\"}".getBytes(), "id: 1\n\n".getBytes());
        RunEventHub.Subscription source = mock(RunEventHub.Subscription.class);
        when(source.read(eq(0L), any())).thenReturn(new RunEventHub.ReadResult(RunEventHub.ReadStatus.FRAME, frame));
        SseEmitter emitter = mock(SseEmitter.class);
        RunSseSubscription subscription = new RunSseSubscription(source, emitter, 0,
                Clock.fixed(now, ZoneOffset.UTC), now.plusSeconds(60), () -> { });

        subscription.ready();
        subscription.run();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(source).close();
    }
}
