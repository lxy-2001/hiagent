package com.agentflow.core.model;

import java.util.List;

/** Embedding adapter that bounds transport including response-body reading. */
public interface DeadlineAwareEmbeddingClient extends EmbeddingClient {
    List<Double> embed(String text, EmbeddingCallOptions options);
}
