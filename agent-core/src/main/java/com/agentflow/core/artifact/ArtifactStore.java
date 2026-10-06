package com.agentflow.core.artifact;
import com.agentflow.core.context.HistoryStore;
import java.time.Instant;
public interface ArtifactStore {
 enum State { WRITING, AVAILABLE, TRUNCATED, DELETING, EVICTED, MISSING, WRITE_FAILED }
 record Ref(String id,String kind,State state,long savedBytes,Long originalBytes,String sha256,String preview,boolean truncated,boolean redacted,Instant createdAt) { }
 record Range(Ref artifact,String content,long startLine,long nextLine,boolean hasMore,boolean available) { }
 Ref save(HistoryStore.Scope scope,String kind,String content,HistoryStore.Io io);
 Ref get(HistoryStore.Scope scope,String id,HistoryStore.Io io);
 Range read(HistoryStore.Scope scope,String id,long startLine,int maxLines,int maxTokens,HistoryStore.Io io);
 AutoCloseable pin(HistoryStore.Scope scope,String id);
}
