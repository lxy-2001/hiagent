package com.agentflow.core.rag;

import java.util.List;

public interface RagRetriever {

    List<RagDocument> retrieve(String query, int limit);
}
