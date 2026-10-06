package com.agentflow.core.context;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import java.util.List;
public interface CompactionModel {
 record Request(HistoryStore.Scope scope,List<ModelMessage> source,String instruction,int targetTokens,HistoryStore.Io io,String requestKey) { public Request { source=List.copyOf(source); if(targetTokens<1) throw new IllegalArgumentException("target required"); } }
 record Result(boolean completed,String summary,TokenUsage usage,UsageSource usageSource,AssistantTurnDecision.UsageDetails usageDetails) { }
 Result summarize(Request request);
}
