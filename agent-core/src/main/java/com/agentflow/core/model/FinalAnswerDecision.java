package com.agentflow.core.model;

import com.agentflow.core.chat.TokenUsage;

import java.util.Objects;

public record FinalAnswerDecision(
        String decisionId,
        String answer,
        TokenUsage usage,
        UsageSource usageSource
) implements ModelDecision {
    public FinalAnswerDecision(String decisionId, String answer, TokenUsage usage) {
        this(decisionId, answer, usage, UsageSource.UNKNOWN);
    }

    public FinalAnswerDecision {
        requireNonBlank(decisionId, "decisionId");
        requireNonBlank(answer, "answer");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(usageSource, "usageSource must not be null");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
