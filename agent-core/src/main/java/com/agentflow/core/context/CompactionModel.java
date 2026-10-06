package com.agentflow.core.context;
import com.agentflow.core.model.*;
import com.agentflow.core.chat.TokenUsage;
import java.util.List;
public interface CompactionModel {
 /** Visible summary target and total generation allowance (including reasoning) are independent. */
 record Request(HistoryStore.Scope scope,List<ModelMessage> source,String instruction,int targetTokens,
                int outputTokens,HistoryStore.Io io,String requestKey) {
  public Request(HistoryStore.Scope scope,List<ModelMessage> source,String instruction,int targetTokens,
                 HistoryStore.Io io,String requestKey) {
   this(scope,source,instruction,targetTokens,32768,io,requestKey);
  }
  public Request {
   source=List.copyOf(source);
   if(targetTokens<1 || outputTokens<1) throw new IllegalArgumentException("positive target and output allowance required");
  }
 }
 record Result(boolean completed,String summary,TokenUsage usage,UsageSource usageSource,AssistantTurnDecision.UsageDetails usageDetails) { }
 Result summarize(Request request);
}