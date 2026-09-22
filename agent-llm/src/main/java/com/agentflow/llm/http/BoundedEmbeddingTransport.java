package com.agentflow.llm.http;

import com.agentflow.core.model.EmbeddingCallOptions;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.llm.ModelClientException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;

/** One reusable client, no per-call thread, and a limit enforced while bytes arrive. */
public final class BoundedEmbeddingTransport {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public byte[] post(URI endpoint, String key, byte[] body, EmbeddingCallOptions options) {
        var control = new ToolExecutionControl(options.cancellationSignal(), TimeSource.system(), options.remainingTime())
                .child(Duration.ofSeconds(5));
        control.checkActive();
        var request = HttpRequest.newBuilder(endpoint).timeout(control.remainingTime())
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var future = client.sendAsync(request, ignored -> new LimitedBody());
        try {
            while (true) {
                control.checkActive();
                try {
                    var response = future.get(Math.min(control.remainingTime().toNanos(), 50_000_000), TimeUnit.NANOSECONDS);
                    control.checkActive();
                    if (response.statusCode() < 200 || response.statusCode() >= 300) { throw unavailable(); }
                    return response.body();
                } catch (TimeoutException pending) { /* Poll cancellation and whole-body deadline. */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("embedding interrupted");
        } catch (ExecutionException failed) {
            throw unavailable();
        } finally {
            if (!future.isDone()) { future.cancel(true); }
        }
    }

    private static ModelClientException unavailable() {
        return new ModelClientException(ModelClientException.PROVIDER_ERROR, "Embedding transport failed");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(unavailable());
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(unavailable()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
