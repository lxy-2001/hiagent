package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SseSendFixtureTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void exposesRealMvcInitializationAndBlockedSendLifecycle() throws Exception {
        SseSendFixture emitter = new SseSendFixture();
        MockMvc mvc = standaloneSetup(new FixtureController(emitter)).build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            mvc.perform(get("/fixture-events"))
                    .andExpect(request().asyncStarted());
            assertThat(emitter.awaitMvcInitialized(WAIT)).isTrue();

            Future<?> send = executor.submit(() -> {
                emitter.send(SseEmitter.event().name("fixture").data("payload"));
                return null;
            });
            assertThat(emitter.awaitSendEntered(WAIT)).isTrue();
            assertThat(send.isDone()).isFalse();
            assertThat(emitter.activeSendCount()).isEqualTo(1);
            assertThat(emitter.maxActiveSendCount()).isEqualTo(1);

            emitter.releaseSend();
            send.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(emitter.awaitSendExited(WAIT)).isTrue();
            assertThat(emitter.activeSendCount()).isZero();
        } finally {
            emitter.releaseSend();
            emitter.complete();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    @Controller
    private static final class FixtureController {
        private final SseEmitter emitter;

        private FixtureController(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @GetMapping("/fixture-events")
        SseEmitter events() {
            return emitter;
        }
    }
}
