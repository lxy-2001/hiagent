package com.agentflow.web.run;

import java.util.List;

public interface RunEventHub {

    enum PublishStatus { PUBLISHED, MISSING, TERMINAL, UNAVAILABLE }

    enum ReplayStatus { AVAILABLE, TOO_OLD, AHEAD, UNAVAILABLE }

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

    boolean create(String taskId);

    PublishResult publish(String taskId, RunEvent.Draft event);

    ReplayResult replay(String taskId, long lastEventId);

    /** Closes publication for a run after its committed terminal event is present. */
    default boolean markTerminal(String taskId) {
        return false;
    }
}
