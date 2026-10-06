package com.agentflow.tool;
import com.agentflow.core.artifact.ArtifactStore;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class ReadArtifactToolTest {
 @Test void derivesOwnerFromContextAndReturnsByteContinuation(){
  ArtifactStore store=new ArtifactStore(){
   public Ref save(HistoryStore.Scope s,String k,String c,HistoryStore.Io i){throw new UnsupportedOperationException();}
   public Ref get(HistoryStore.Scope s,String id,HistoryStore.Io i){return new Ref(id,"TOOL_OUTPUT",State.AVAILABLE,6,6L,null,"中文",false,false,java.time.Instant.now());}
   public Range read(HistoryStore.Scope s,String id,long offset,int count,int tokens,HistoryStore.Io i){assertThat(s.userId()).isEqualTo("owner");assertThat(s.runId()).isEqualTo("run");assertThat(offset).isEqualTo(0);return new Range(get(s,id,i),"中文",0,6,true);}
   public AutoCloseable pin(HistoryStore.Scope s,String id){return ()->{};}
  };
  var result=new ReadArtifactTool(store,new tools.jackson.databind.ObjectMapper()).execute(new ToolArguments(Map.of("artifactId","opaque")),new ToolContext("run","session","owner"));
  assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);assertThat(result.output()).contains("nextOffsetBytes", "6", "中文");
  assertThat(new ReadArtifactTool(store,new tools.jackson.databind.ObjectMapper()).execute(new ToolArguments(Map.of("artifactId","opaque","maxBytes",999999)),new ToolContext("run","session","owner")).status()).isEqualTo(ToolResultStatus.FAILED);
 }
}
