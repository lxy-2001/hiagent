package com.agentflow.web.run;

import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class SseSendFixture extends SseEmitter {

    private static final Duration INTERNAL_WAIT_LIMIT = Duration.ofSeconds(5);

    private final CountDownLatch mvcInitialized = new CountDownLatch(1);
    private final CountDownLatch sendEntered = new CountDownLatch(1);
    private final CountDownLatch sendRelease = new CountDownLatch(1);
    private final CountDownLatch sendExited = new CountDownLatch(1);
    private final AtomicInteger activeSends = new AtomicInteger();
    private final AtomicInteger maxActiveSends = new AtomicInteger();

    SseSendFixture() {
        super(Duration.ofMinutes(1).toMillis());
    }

    @Override
    protected void extendResponse(ServerHttpResponse outputMessage) {
        super.extendResponse(outputMessage);
        mvcInitialized.countDown();
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        Objects.requireNonNull(builder, "builder must not be null");
        int activeNow = activeSends.incrementAndGet();
        maxActiveSends.accumulateAndGet(activeNow, Math::max);
        sendEntered.countDown();
        try {
            if (!sendRelease.await(INTERNAL_WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("SSE send fixture was not released within " + INTERNAL_WAIT_LIMIT);
            }
            super.send(builder);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("SSE send fixture wait was interrupted", interrupted);
        } finally {
            activeSends.decrementAndGet();
            sendExited.countDown();
        }
    }

    boolean awaitMvcInitialized(Duration timeout) throws InterruptedException {
        return await(mvcInitialized, timeout);
    }

    boolean awaitSendEntered(Duration timeout) throws InterruptedException {
        return await(sendEntered, timeout);
    }

    boolean awaitSendExited(Duration timeout) throws InterruptedException {
        return await(sendExited, timeout);
    }

    void releaseSend() {
        sendRelease.countDown();
    }

    int activeSendCount() {
        return activeSends.get();
    }

    int maxActiveSendCount() {
        return maxActiveSends.get();
    }

    private static boolean await(CountDownLatch latch, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
