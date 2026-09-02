package com.agentflow.llm;

import com.agentflow.core.model.EmbeddingClient;

import java.util.List;
import java.util.Objects;

/**
 * Embedding adapter backed by the shared OpenAI-compatible transport.
 */
public final class OpenAiEmbeddingClient implements EmbeddingClient {

    private final OpenAiCompatibleModelClient transport;

    public OpenAiEmbeddingClient(OpenAiCompatibleModelClient transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public List<Double> embed(String text) {
        return transport.embed(text);
    }
}
