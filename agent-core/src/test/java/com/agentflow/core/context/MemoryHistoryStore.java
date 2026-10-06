package com.agentflow.core.context;

import com.agentflow.core.model.ModelMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Deterministic durable fixture. No model or tool execution in storage. */
public final class MemoryHistoryStore implements HistoryStore {
    public final List<Record> records = new ArrayList<>();
    public Checkpoint checkpoint;
    public long revision;
    public int reads;
    public int saves;
    public long requestedAfter;
    public RuntimeException saveFailure;

    public void add(String run, Item item) {
        records.add(new Record(records.size()+1, run, item, item.message()==null ? 64 : 64+item.message().content().length()*2L));
        revision++;
    }
    public static Item message(Kind kind, ModelMessage message, String callId) {
        return new Item(kind, message, Instant.EPOCH, null, callId, null, null, null);
    }
    @Override public State state(Scope scope, Io io) { io.check(); return new State(records.size(), revision, checkpoint); }
    @Override public Page readAfter(Scope scope,long after,long until,int limit,long byteBudget,Io io) {
        io.check(); reads++; requestedAfter=after;
        List<Record> page = new ArrayList<>(); long bytes=0;
        for (Record record:records) {
            if(record.sequence()<=after || record.sequence()>until) continue;
            if(page.size()==limit || bytes+record.payloadBytes()>byteBudget) break;
            page.add(record); bytes+=record.payloadBytes();
        }
        long next=page.isEmpty()?after:page.get(page.size()-1).sequence();
        return new Page(page,next,next<until);
    }
    @Override public Batch appendBatch(Scope scope,String id,String hash,List<Item> items,Io io) {
        io.check(); long first=records.size()+1;
        for(Item item:items) add(scope.runId(),item);
        return new Batch(first,records.size(),revision);
    }
    @Override public Checkpoint saveCheckpoint(Scope scope,long expected,Checkpoint candidate,Io io) {
        io.check(); saves++;
        if(saveFailure!=null) throw saveFailure;
        if(expected!=revision) throw new IllegalStateException("CHECKPOINT_REVISION_CONFLICT");
        checkpoint=candidate; revision=candidate.revision(); return checkpoint;
    }
}
