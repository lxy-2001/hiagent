package com.agentflow.llm;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolDefinition;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import java.util.*;
/** Counts full inputs, with a bounded cache. No character/message truncation. */
public final class JtokkitTokenEstimator implements TokenEstimator {
 private final Encoding encoding; private final String encodingName;
 private final Map<String,Integer> cache=new LinkedHashMap<>(128,0.75f,true){ protected boolean removeEldestEntry(Map.Entry<String,Integer> e){return size()>128;} };
 public JtokkitTokenEstimator(String name) { encodingName=Objects.requireNonNull(name); encoding=Encodings.newDefaultEncodingRegistry().getEncoding(name).orElseThrow(()->new IllegalArgumentException("unknown token encoding: "+name)); }
 private long count(String text){ if(text==null) return 0; if(text.length()>8192) return encoding.countTokens(text); synchronized(cache){return cache.computeIfAbsent(text,encoding::countTokens);} }
 public long estimateInput(List<ModelMessage> messages,List<ToolDefinition> tools){long total=8; for(var m:messages){total+=8+count(m.role())+count(m.content())+count(m.name())+count(m.toolCallId()); if(m.toolCall()!=null) total+=count(m.toolCall().name())+count(m.toolCall().arguments().values().toString());} for(var t:tools) total+=24+count(t.name())+count(t.description())+count(t.schema().toString()); return total; }
 public String version(){return "jtokkit-1.1.0:"+encodingName;} }
