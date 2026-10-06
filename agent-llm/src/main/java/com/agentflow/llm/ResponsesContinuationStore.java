package com.agentflow.llm;

import com.agentflow.core.context.HistoryStore;
import tools.jackson.databind.JsonNode;
import java.util.Optional;

/** Opaque provider state only; callers must confirm its reference with durable history before executing tools. */
public interface ResponsesContinuationStore {
    record Scope(String userId,String sessionId,String runId,String provider,String model) { }
    String stage(Scope scope,String requestKey,JsonNode output,Long reasoningTokenHint,HistoryStore.Io io);
    Optional<JsonNode> read(Scope scope,String reference,HistoryStore.Io io);
    void prune(Scope scope,HistoryStore.Io io);
}
