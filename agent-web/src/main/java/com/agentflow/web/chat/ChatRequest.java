package com.agentflow.web.chat;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        String sessionId,
        @NotBlank String message,
        String systemPrompt,
        String model,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @Min(1) Integer maxTokens
) {
}
