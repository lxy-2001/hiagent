package com.agentflow.core.model;

import com.agentflow.core.chat.TokenUsage;

import java.util.Objects;

public record FinalAnswerDecision(
        String decisionId,
        String answer,
        TokenUsage usage
) implements ModelDecision {
    public FinalAnswerDecision {
        requireNonBlank(decisionId, "decisionId");
        requireNonBlank(answer, "answer");
        Objects.requireNonNull(usage, "usage must not be null");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
