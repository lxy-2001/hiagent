package com.agentflow.core.context;

import java.util.Objects;

/** Immutable successful conversation turn used as a context candidate. */
public record ConversationTurn(String runId, long turnSequence, String userText, String assistantText) {
    public ConversationTurn {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (turnSequence <= 0) {
            throw new IllegalArgumentException("turnSequence must be positive");
        }
        Objects.requireNonNull(userText, "userText must not be null");
        Objects.requireNonNull(assistantText, "assistantText must not be null");
        if (userText.length() > 8000 || assistantText.length() > 65536) {
            throw new IllegalArgumentException("conversation text exceeds context limits");
        }
    }
}
