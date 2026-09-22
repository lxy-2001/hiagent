package com.agentflow.web.conversation;

import java.time.Duration;
import java.util.Objects;

/** Application-owned preparation deadline; never extends the two-second source limit. */
public record ConversationProperties(Duration sourceTimeout) {
    public ConversationProperties {
        Objects.requireNonNull(sourceTimeout, "sourceTimeout must not be null");
        if (sourceTimeout.isZero() || sourceTimeout.isNegative() || sourceTimeout.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException("sourceTimeout must be within (0, 2 seconds]");
        }
    }
    public static ConversationProperties defaults() { return new ConversationProperties(Duration.ofSeconds(2)); }
}
