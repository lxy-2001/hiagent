package com.agentflow.core.artifact;
import com.agentflow.core.context.HistoryStore;
import java.time.Instant;
/** Owner-scoped managed UTF-8 content; byte ranges preserve character boundaries. */
public interface ArtifactStore {
 enum State { WRITING, AVAILABLE, TRUNCATED, DELETING, EVICTED, MISSING, WRITE_FAILED }
 record Ref(String id,String kind,State state,long savedBytes,Long originalBytes,String sha256,String preview,boolean truncated,boolean redacted,Instant createdAt) { }
 record Range(Ref artifact,String content,long actualOffsetBytes,long nextOffsetBytes,boolean eof) { }
 Ref save(HistoryStore.Scope scope,String kind,String content,HistoryStore.Io io);
 /** A pre-truncated source must not be persisted as an apparently complete attachment. */
 default Ref save(HistoryStore.Scope scope,String kind,String content,boolean sourceTruncated,HistoryStore.Io io) {
  if (sourceTruncated) throw new IllegalStateException("source truncation metadata unsupported");
  return save(scope,kind,content,io);
 }
 Ref get(HistoryStore.Scope scope,String id,HistoryStore.Io io);
 Range read(HistoryStore.Scope scope,String id,long offsetBytes,int maxBytes,int maxTokens,HistoryStore.Io io);
 AutoCloseable pin(HistoryStore.Scope scope,String id);
}
