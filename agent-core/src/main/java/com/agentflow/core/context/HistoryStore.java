package com.agentflow.core.context;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.model.ModelMessage;
import java.time.*;
import java.util.*;
/** Durable I/O. Implementations confirm writes, never execute model or tool work. */
public interface HistoryStore {
 record Scope(String userId,String sessionId,String runId) { public Scope { if(userId==null||userId.isBlank()||sessionId==null||sessionId.isBlank()||runId==null||runId.isBlank()) throw new IllegalArgumentException("scope required"); } }
 record Io(CancellationSignal cancellation,Instant deadline) { public Io { Objects.requireNonNull(cancellation); Objects.requireNonNull(deadline); } public void check(){if(cancellation.isCancelled()) throw new IllegalStateException("CANCELLED"); if(!Instant.now().isBefore(deadline)) throw new IllegalStateException("TIMED_OUT");} }
 enum Kind { USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_CALL, TOOL_EXECUTION_INTENT, TOOL_RESULT, RUN_TERMINAL, RECOVERY_NOTICE }
 record Item(Kind kind,ModelMessage message,Instant occurredAt,String decisionId,String callId,String status,String artifactId,String adapterStateRef) { public Item { Objects.requireNonNull(kind); Objects.requireNonNull(occurredAt); } }
 record Record(long sequence,String runId,Item item,long payloadBytes) { }
 record State(long lastSequence,long revision,Checkpoint checkpoint) { }
 record Page(List<Record> records,long nextSequence,boolean hasMore) { public Page { records=List.copyOf(records); } }
 record Batch(long firstSequence,long lastSequence,long revision) { }
 record Checkpoint(String id,long revision,String baseId,long coveredThrough,String summary,long summaryTokens,long sourceTokens,long retainedTokens,long tailStart,String requestKey,String promptVersion,String profileHash,Instant createdAt) { }
 State state(Scope scope,Io io);
 Page readAfter(Scope scope,long after,long until,int limit,long byteBudget,Io io);
 Batch appendBatch(Scope scope,String mutationId,String payloadHash,List<Item> items,Io io);
 Checkpoint saveCheckpoint(Scope scope,long expectedRevision,Checkpoint checkpoint,Io io);
}
