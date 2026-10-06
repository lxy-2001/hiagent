package com.agentflow.web.context;
import com.agentflow.core.context.*;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.cancel.CancellationSignal;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ContextSessionServiceTest {
 @Test void restoresOnceAndAppendsNewRunWithoutReloadingOldBodies() {
  var history=new MemoryHistory(); var service=new ContextSessionService(history,new Utf8TokenEstimator(),new ContextPolicy("trusted rules","test",272000));
  var io=new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(60));
  history.add("r1","first question");
  try(var session=service.open(new HistoryStore.Scope("u","s","r1"),io)) {assertThat(session.prepare(List.of(),32768,1)).anyMatch(m->m.content().equals("first question"));}
  history.add("r2","second question");
  try(var session=service.open(new HistoryStore.Scope("u","s","r2"),io)) {assertThat(session.prepare(List.of(),32768,1)).anyMatch(m->m.content().equals("second question"));}
  assertThat(history.starts).containsExactly(0L,1L);
  assertThat(service.cachedSessions()).isEqualTo(1);
 }
 @Test void externalizesLargeResultsAndPreservesFailureDetailsInTheConfirmedTail() {
  var history=new MemoryHistory(); history.add("r1","question");
  var artifacts=new com.agentflow.core.artifact.ArtifactStore(){
   public Ref save(HistoryStore.Scope s,String k,String content,HistoryStore.Io io){return new Ref("artifact-1",k,State.AVAILABLE,content.length(),(long)content.length(),"hash","small preview",false,false,Instant.now());}
   public Ref get(HistoryStore.Scope s,String id,HistoryStore.Io io){throw new UnsupportedOperationException();}
   public Range read(HistoryStore.Scope s,String id,long offset,int bytes,int tokens,HistoryStore.Io io){throw new UnsupportedOperationException();}
   public AutoCloseable pin(HistoryStore.Scope s,String id){return ()->{};}
  };
  var service=new ContextSessionService(history,new Utf8TokenEstimator(),new ContextPolicy("rules","p",272000),null,null,artifacts,"profile",o->{});
  try(var session=service.open(new HistoryStore.Scope("u","s","r1"),new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(60)))){
   var compact=session.result(new com.agentflow.core.tool.ToolResult("large","x".repeat(30000),com.agentflow.core.tool.ToolResultStatus.SUCCESS,null,null,false,"c1"));
   assertThat(compact.output()).contains("artifact-1","small preview").hasSizeLessThan(1000);
   session.result(com.agentflow.core.tool.ToolResult.failure("test","c2","EXIT_1","assertion failed"));
   assertThat(session.prepare(List.of(),32768,1)).anyMatch(m->m.content().contains("EXIT_1")&&m.content().contains("assertion failed"));
   assertThat(history.records).anyMatch(r->"artifact-1".equals(r.item().artifactId()));
   var range=session.result(new com.agentflow.core.tool.ToolResult("read-artifact","a".repeat(25000),com.agentflow.core.tool.ToolResultStatus.SUCCESS,null,null,false,"c3"));
   assertThat(range.output()).isEqualTo("a".repeat(25000));
  }
 }
 @Test void obtainedResultIsSavedAfterCancellationWithoutStartingAnotherAction() {
  var history=new MemoryHistory();history.add("r1","question");var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
  var service=new ContextSessionService(history,new Utf8TokenEstimator(),new ContextPolicy("rules","p",272000));
  try(var session=service.open(new HistoryStore.Scope("u","s","r1"),new HistoryStore.Io(cancelled::get,Instant.now().plusSeconds(60)))){
   cancelled.set(true);
   session.result(com.agentflow.core.tool.ToolResult.failure("tool","c1","CANCELLED","cancelled before dispatch"));
   assertThat(history.records).anyMatch(r->r.item().kind()==HistoryStore.Kind.TOOL_RESULT&&r.item().message().content().contains("CANCELLED"));
   assertThatThrownBy(()->session.prepare(List.of(),32768,2)).hasMessage("CANCELLED");
  }
 }
 @Test void reservesConfiguredCacheCapacityAndEvictsOnlyIdleSessions() {
  var history=new MemoryHistory();history.add("r0","short question");
  var service=new ContextSessionService(history,new Utf8TokenEstimator(),new ContextPolicy("rules","p",272000),
   null,null,null,"profile",o->{},new ContextSessionService.CacheLimits(3,1024,2048),(scope,io)->{});
  var io=new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(60));
  try(var first=service.open(new HistoryStore.Scope("u","s1","r1"),io);
      var second=service.open(new HistoryStore.Scope("u","s2","r2"),io)) {
   assertThatThrownBy(()->service.open(new HistoryStore.Scope("u","s3","r3"),io)).hasMessage("CONTEXT_BUDGET_EXCEEDED");
   assertThat(service.cachedSessions()).isEqualTo(2);
  }
  try(var third=service.open(new HistoryStore.Scope("u","s3","r3"),io)) {assertThat(service.cachedSessions()).isEqualTo(2);}
 }
 @Test void rejectsRestoreBeyondConfiguredSessionMemoryBeforeModelWork() {
  var history=new MemoryHistory();history.add("r1","x".repeat(800));
  var service=new ContextSessionService(history,new Utf8TokenEstimator(),new ContextPolicy("rules","p",272000),
   null,null,null,"profile",o->{},new ContextSessionService.CacheLimits(2,1024,2048),(scope,io)->{});
  assertThatThrownBy(()->service.open(new HistoryStore.Scope("u","s","r1"),
   new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(60)))).hasMessage("CONTEXT_BUDGET_EXCEEDED");
  assertThat(service.cachedSessions()).isZero();
 }
 @Test void confirmsRecoveryBeforeRestoringOrRefreshingTheWorkingTail() {
  var history=new MemoryHistory();var recoveries=new ArrayList<String>();
  var service=new ContextSessionService(history,new Utf8TokenEstimator(),new ContextPolicy("rules","p",272000),
   null,null,null,"profile",o->{},ContextSessionService.CacheLimits.defaults(),(scope,io)->{
    io.check();recoveries.add(scope.runId());history.add(scope.runId(),"recovery fact "+scope.runId());
   });
  var io=new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(60));
  try(var first=service.open(new HistoryStore.Scope("u","s","r1"),io)) {
   assertThat(first.prepare(List.of(),32768,1)).anyMatch(m->m.content().equals("recovery fact r1"));
  }
  try(var second=service.open(new HistoryStore.Scope("u","s","r2"),io)) {
   assertThat(second.prepare(List.of(),32768,1)).anyMatch(m->m.content().equals("recovery fact r2"));
  }
  assertThat(recoveries).containsExactly("r1","r2");
  assertThat(history.starts).containsExactly(0L,1L);
 }
 @Test void stopsCompactionRetryWhenFirstSummaryExhaustsCumulativeRunInputBudget() {
  var history=new MemoryHistory();
  for(int i=0;i<10;i++) history.add("old","x".repeat(1700));
  TokenEstimator estimator=new TokenEstimator(){
   public long estimateInput(List<ModelMessage> messages,List<com.agentflow.core.tool.ToolDefinition> tools){return messages.stream().mapToLong(m->m.content().length()+8L).sum();}
   public String version(){return "deterministic-test";}
  };
  var summaries=new java.util.concurrent.atomic.AtomicInteger();
  var mainModelCalls=new java.util.concurrent.atomic.AtomicInteger();
  CompactionModel model=request->{
   summaries.incrementAndGet();
   return new CompactionModel.Result(false,"",new com.agentflow.core.chat.TokenUsage(19000,10,19010),
    com.agentflow.core.model.UsageSource.REPORTED,null);
  };
  var limits=new ContextCompactor.Limits(20000,.80,1000,200,.20,1000,3000);
  var compactor=new ContextCompactor(model,estimator,new CompactionPrompt("compact task history"),text->true);
  var service=new ContextSessionService(history,estimator,new ContextPolicy("rules","p",20000),compactor,limits,null,"profile",o->{});
  var runtime=new com.agentflow.core.runtime.DefaultAgentRuntime(request->{
   mainModelCalls.incrementAndGet();
   return new com.agentflow.core.model.FinalAnswerDecision("d1","done",com.agentflow.core.chat.TokenUsage.empty());
  },new com.agentflow.tool.InMemoryToolRegistry(),step->{});
  try(var session=service.open(new HistoryStore.Scope("u","s","r1"),new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(60)))){
   var result=runtime.run(new com.agentflow.core.AgentRequest("r1","s","u","continue"),event->{},
    new com.agentflow.core.runtime.AgentRunOptions(new com.agentflow.core.runtime.ExecutionBudget(3,Duration.ofSeconds(60),19000,2000),
     CancellationSignal.NONE,null,Duration.ofSeconds(30),session));
   assertThat(summaries.get()).isEqualTo(1);
   assertThat(mainModelCalls.get()).isZero();
   assertThat(result.terminationReason()).isEqualTo(com.agentflow.core.runtime.TerminationReason.BUDGET_EXCEEDED);
   assertThat(result.usage().promptTokens()).isEqualTo(19000);
  }
 }
 static class MemoryHistory implements HistoryStore {
  List<Record> records=new ArrayList<>(); List<Long> starts=new ArrayList<>();
  void add(String run,String text){records.add(new Record(records.size()+1,run,new Item(Kind.USER_MESSAGE,ModelMessage.user(text),Instant.now(),null,null,null,null,null),text.length()*2L));}
  public State state(Scope s,Io io){return new State(records.size(),records.size(),null);}
  public Page readAfter(Scope s,long after,long until,int limit,long bytes,Io io){starts.add(after);var list=records.stream().filter(r->r.sequence()>after && r.sequence()<=until).toList();return new Page(list,until,false);}
  public Batch appendBatch(Scope s,String key,String hash,List<Item> items,Io io){io.check();long first=records.size()+1;for(var item:items)records.add(new Record(records.size()+1,s.runId(),item,192));return new Batch(first,records.size(),records.size());}
  public Checkpoint saveCheckpoint(Scope s,long revision,Checkpoint checkpoint,Io io){throw new UnsupportedOperationException();}
 }
}
