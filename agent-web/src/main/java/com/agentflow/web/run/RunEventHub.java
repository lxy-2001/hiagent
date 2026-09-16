package com.agentflow.web.run;

import java.util.List;
import java.time.Duration;

public interface RunEventHub {

    enum PublishStatus { PUBLISHED, MISSING, TERMINAL, UNAVAILABLE }

    enum ReplayStatus { AVAILABLE, TOO_OLD, AHEAD, UNAVAILABLE }

    enum OpenStatus { OPEN, DONE, TOO_OLD, AHEAD, UNAVAILABLE, CAPACITY }

    enum ReadStatus { FRAME, IDLE, DONE, TOO_OLD, UNAVAILABLE }

    record PublishedFrame(RunEvent event, byte[] jsonBytes, byte[] sseBytes) {
        public PublishedFrame {
            jsonBytes = jsonBytes.clone();
            sseBytes = sseBytes.clone();
        }

        @Override
        public byte[] jsonBytes() {
            return jsonBytes.clone();
        }

        @Override
        public byte[] sseBytes() {
            return sseBytes.clone();
        }
    }

    record PublishResult(PublishStatus status, PublishedFrame frame) {
    }

    record ReplayResult(ReplayStatus status, long earliestId, long latestId,
                        List<PublishedFrame> frames) {
        public ReplayResult {
            frames = List.copyOf(frames);
        }
    }

    record OpenResult(OpenStatus status, Subscription subscription) { }

    record ReadResult(ReadStatus status, PublishedFrame frame) { }

    interface Subscription extends AutoCloseable {
        ReadResult read(long cursor, Duration maximumWait) throws InterruptedException;
        default void wake() { }
        @Override void close();
    }

    boolean create(String taskId);

    PublishResult publish(String taskId, RunEvent.Draft event);

    ReplayResult replay(String taskId, long lastEventId);

    default OpenResult open(String taskId, long lastEventId) {
        return new OpenResult(OpenStatus.UNAVAILABLE, null);
    }

    default void closeSubscriptions() { }

    default void maintain() { }

    /** Closes publication for a run after its committed terminal event is present. */
    default boolean markTerminal(String taskId) {
        return false;
    }
}
