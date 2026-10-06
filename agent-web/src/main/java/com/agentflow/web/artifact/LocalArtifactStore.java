package com.agentflow.web.artifact;

import com.agentflow.core.artifact.ArtifactStore;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.context.*;
import com.agentflow.web.context.BoundedPersistenceRetry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** Single-process reservations and pins; database and file I/O never hold the quota monitor. */
public final class LocalArtifactStore implements ArtifactStore {
 public record Limits(long fileBytes,long sessionBytes,long sessionTarget,long totalBytes,long totalTarget) {
  public Limits {if(fileBytes<4||fileBytes>(64L<<20)||sessionTarget<fileBytes||sessionBytes<=sessionTarget||totalTarget<sessionBytes||totalBytes<=totalTarget||totalBytes>Long.MAX_VALUE-fileBytes)throw new IllegalArgumentException("invalid artifact limits");}
  public static Limits defaults(){return new Limits(64L<<20,512L<<20,384L<<20,5L<<30,4L<<30);}
 }
 private static final String COLUMNS="id,owner_id,session_id,run_id,kind,state,relative_path,saved_bytes,original_bytes,sha256,truncated,redacted,created_at,last_accessed_at";
 private static final String LIVE="(saved_bytes>0 or state in ('WRITING','DELETING','WRITE_FAILED'))";
 private static final String UNFINISHED="status not in ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','BUDGET_EXCEEDED')";
 private static final class Meta {
  // Previews and settled tombstones remain in SQL, not in this quota index.
  String id,owner,session,run,kind,path,hash; State state; long bytes; Long original;
  boolean truncated,redacted; Instant created,access; int pins;
  Ref ref(String preview){return new Ref(id,kind,state,bytes,original,hash,preview,truncated,redacted,created);}
 }
 private record Detail(Meta meta,String preview) {}
 private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final Path root;
 private final TokenEstimator estimator; private final Limits limits; private final Object quota=new Object();
 private final Map<String,Meta> entries=new HashMap<>(); private final BoundedPersistenceRetry retry;
 private final ArtifactAdmissionCoordinator admissions=new ArtifactAdmissionCoordinator();
 private final int previewTokens,readTokens; private final Set<String> trimSessions=new HashSet<>(); private boolean trimGlobal;
 public LocalArtifactStore(JdbcTemplate jdbc,PlatformTransactionManager manager,Path dataRoot,TokenEstimator estimator,Limits limits){
  this(jdbc,manager,dataRoot,estimator,limits,4096,8192,new BoundedPersistenceRetry(Clock.systemUTC()));
 }
 public LocalArtifactStore(JdbcTemplate jdbc,PlatformTransactionManager manager,Path dataRoot,TokenEstimator estimator,Limits limits,int previewTokens,int readTokens,BoundedPersistenceRetry retry){
  if(previewTokens<1||previewTokens>4096||readTokens<1||readTokens>8192)throw new IllegalArgumentException("Invalid artifact token limits");
  this.previewTokens=previewTokens;this.readTokens=readTokens;this.retry=Objects.requireNonNull(retry);
  this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);tx.setTimeout(3);this.estimator=estimator;this.limits=limits;
  try{Path candidate=dataRoot.toAbsolutePath().normalize().resolve("artifacts");Files.createDirectories(candidate);if(Files.isSymbolicLink(candidate)||Files.isSymbolicLink(candidate.getParent()))throw fail("ARTIFACT_PATH_INVALID");root=candidate.toRealPath();}catch(IOException e){throw new UncheckedIOException(e);}
  String cursor="";
  while(true){
   var page=jdbc.query("select "+COLUMNS+" from agent_artifact where "+LIVE+" and id>? order by id limit 128",(rs,row)->metadata(rs),cursor);
   if(page.isEmpty())break;
   for(Meta m:page)entries.put(m.id,m);
   cursor=page.get(page.size()-1).id;
  }
  reconcile();
  trimToConfiguredQuota(32,new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(10)));
 }
 public ArtifactAdmissionCoordinator admissions(){return admissions;}
 public Ref save(HistoryStore.Scope scope,String kind,String content,HistoryStore.Io io){return save(scope,kind,content,false,io);}
 @Override public Ref save(HistoryStore.Scope scope,String kind,String content,boolean sourceTruncated,HistoryStore.Io io){
  owned(scope);io.check();if(kind==null||!kind.matches("[A-Z][A-Z0-9_]{0,31}"))throw fail("INVALID_ARTIFACT_KIND");Objects.requireNonNull(content);
  String safe=redact(content);byte[] all=safe.getBytes(StandardCharsets.UTF_8);int n=(int)Math.min(all.length,limits.fileBytes());while(n<all.length&&n>0&&(all[n]&0xc0)==0x80)n--;
  byte[] bytes=Arrays.copyOf(all,n);Meta m=new Meta();m.id=UUID.randomUUID().toString();m.owner=scope.userId();m.session=scope.sessionId();m.run=scope.runId();m.kind=kind;m.state=State.WRITING;m.path=m.session+"/"+m.id+".txt";m.bytes=n;m.original=sourceTruncated?null:(long)all.length;m.truncated=sourceTruncated||n<all.length;m.redacted=!safe.equals(content)||content.contains("[redacted]");m.created=m.access=Instant.now();
  String preview=preview(new String(bytes,StandardCharsets.UTF_8),previewTokens);
  reserve(m,io);
  Path file=null,temp=null;
  try{
   retry.execute(io,"ARTIFACT_STORAGE_FAILED",()->tx.execute(status->{if(jdbc.queryForObject("select count(*) from agent_artifact where id=? and owner_id=? and session_id=? and relative_path=?",Long.class,m.id,m.owner,m.session,m.path)==1)return true;
   jdbc.update("insert into agent_artifact(id,owner_id,session_id,run_id,kind,state,relative_path,saved_bytes,original_bytes,sha256,preview,truncated,redacted,created_at,last_accessed_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",m.id,m.owner,m.session,m.run,m.kind,m.state.name(),m.path,m.bytes,m.original,null,preview,m.truncated,m.redacted,m.created,m.access);return true;}));
   file=path(m,true);temp=file.resolveSibling(file.getFileName()+".tmp");final Path target=file,tmp=temp;
   retry.execute(io,"ARTIFACT_STORAGE_FAILED",()->{try{io.check();try(var channel=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()){io.check();channel.write(buffer);}channel.force(true);}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return true;}catch(IOException e){throw new UncheckedIOException(e);}});
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   synchronized(quota){m.hash=hash;}
   confirmState(m,m.truncated?State.TRUNCATED:State.AVAILABLE,m.bytes,io);
   synchronized(quota){return m.ref(preview);}
  }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  catch(RuntimeException e){
   synchronized(quota){m.state=State.WRITE_FAILED;}
   try{if(temp!=null)Files.deleteIfExists(temp);if(file!=null)Files.deleteIfExists(file);confirmState(m,State.WRITE_FAILED,0,new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(10)));}
   catch(Exception ignored){/* Startup reconciles unconfirmed state; reservation remains counted. */}
   throw e instanceof ContextSessionException?e:new ContextSessionException("ARTIFACT_STORAGE_FAILED",e);
  }
 }
 public Ref get(HistoryStore.Scope scope,String id,HistoryStore.Io io){
  owned(scope);io.check();Detail stored=detail(scope,id);
  synchronized(quota){Meta m=entries.getOrDefault(id,stored.meta());checkOwner(scope,m);return m.ref(stored.preview());}
 }
 public AutoCloseable pin(HistoryStore.Scope scope,String id){
  owned(scope);Detail stored=detail(scope,id);Meta m;
  synchronized(quota){m=forUse(scope,stored);if(!available(m))throw fail("ARTIFACT_UNAVAILABLE");m.pins++;entries.put(m.id,m);}
  var released=new java.util.concurrent.atomic.AtomicBoolean();return ()->{if(released.compareAndSet(false,true))synchronized(quota){m.pins--;forgetSettled(m);}};
 }
 public Range read(HistoryStore.Scope scope,String id,long offset,int maxBytes,int maxTokens,HistoryStore.Io io){
  if(offset<0||maxBytes<1||maxBytes>65536||maxTokens<1||maxTokens>8192)throw fail("INVALID_ARTIFACT_RANGE");
  int tokenLimit=Math.min(maxTokens,readTokens);owned(scope);io.check();Detail stored=detail(scope,id);Meta m;
  synchronized(quota){m=forUse(scope,stored);if(!available(m))throw fail("ARTIFACT_"+m.state);if(offset>m.bytes)throw fail("INVALID_ARTIFACT_RANGE");m.pins++;entries.put(m.id,m);}
  try{
   Path file=path(m,false);byte[] bytes;
   try(var channel=FileChannel.open(file,StandardOpenOption.READ)){
    if(offset<m.bytes){ByteBuffer one=ByteBuffer.allocate(1);channel.read(one,offset);if((one.array()[0]&0xc0)==0x80)throw fail("INVALID_ARTIFACT_RANGE");}
    int count=(int)Math.min(maxBytes,m.bytes-offset);ByteBuffer buffer=ByteBuffer.allocate(count+4);channel.position(offset);while(buffer.hasRemaining()&&channel.read(buffer)>0){io.check();}
    int end=Math.min(count,buffer.position());while(end<buffer.position()&&end>0&&(buffer.array()[end]&0xc0)==0x80)end--;bytes=Arrays.copyOf(buffer.array(),end);
   }
   String text=preview(new String(bytes,StandardCharsets.UTF_8),tokenLimit);long next=offset+text.getBytes(StandardCharsets.UTF_8).length;if(next==offset&&offset<m.bytes)throw fail("INVALID_ARTIFACT_RANGE");
   synchronized(quota){m.access=Instant.now();}persist(m,io);
   tx.executeWithoutResult(status->jdbc.update("update agent_session_context set last_used_at=? where session_id=?",m.access,m.session));
   synchronized(quota){return new Range(m.ref(stored.preview()),text,offset,next,next>=m.bytes);}
  }catch(NoSuchFileException e){confirmState(m,State.MISSING,0,io);throw fail("ARTIFACT_MISSING");}
  catch(IOException e){throw new ContextSessionException("ARTIFACT_STORAGE_FAILED",e);}
  finally{synchronized(quota){m.pins--;forgetSettled(m);}}
 }
 private void reserve(Meta incoming,HistoryStore.Io io){
  Set<String> protectedSessions=new HashSet<>();
  while(true){
   io.check();Meta victim;boolean local;Map<String,Instant> last=lastUsed();Set<String> active=activeSessions();
   synchronized(quota){
    local=Math.addExact(used(incoming.session),incoming.bytes)>limits.sessionBytes();boolean global=Math.addExact(used(null),incoming.bytes)>limits.totalBytes();
    if(!local&&!global){entries.put(incoming.id,incoming);return;}
    victim=candidate(incoming.session,local,last,active,protectedSessions);if(victim==null)throw fail("ARTIFACT_STORAGE_FAILED");victim.state=State.DELETING;
   }
   if(!evict(victim,!local,io)){protectedSessions.add(victim.session);continue;}
   // Continue to the low water mark, unless protected data leaves only enough room for this write.
   while(true){
    io.check();Meta extra;boolean localExtra;Map<String,Instant> ages=lastUsed();Set<String> running=activeSessions();
    synchronized(quota){
     localExtra=Math.addExact(used(incoming.session),incoming.bytes)>limits.sessionTarget();boolean globalExtra=Math.addExact(used(null),incoming.bytes)>limits.totalTarget();if(!localExtra&&!globalExtra)break;
     extra=candidate(incoming.session,localExtra,ages,running,protectedSessions);if(extra==null)break;extra.state=State.DELETING;
    }
    if(!evict(extra,!localExtra,io))protectedSessions.add(extra.session);
   }
  }
 }
 /** Caller holds quota; activity queries and deletion happen after leaving it. */
 private Meta candidate(String session,boolean local,Map<String,Instant> ages,Set<String> active,Set<String> protectedSessions){
  return entries.values().stream().filter(m->available(m)&&m.pins==0&&(local?m.session.equals(session):!active.contains(m.session)&&!protectedSessions.contains(m.session)))
   .min(Comparator.comparing((Meta m)->local?Instant.EPOCH:ages.getOrDefault(m.session,Instant.EPOCH)).thenComparing(m->m.access).thenComparing(m->m.id)).orElse(null);
 }
 public void trimToConfiguredQuota(int maxFiles,HistoryStore.Io io){
  if(maxFiles<1||maxFiles>32)throw new IllegalArgumentException("maxFiles must be 1..32");Set<String> protectedSessions=new HashSet<>();
  for(int count=0;count<maxFiles;count++){
   io.check();var active=activeSessions();var ages=lastUsed();Meta victim;
   synchronized(quota){
    entries.values().stream().map(m->m.session).distinct().filter(s->used(s)>limits.sessionBytes()).forEach(trimSessions::add);
    trimSessions.removeIf(s->used(s)<=limits.sessionTarget());trimGlobal=trimGlobal?used(null)>limits.totalTarget():used(null)>limits.totalBytes();
    if(!trimGlobal&&trimSessions.isEmpty())return;
    victim=entries.values().stream().filter(m->available(m)&&m.pins==0&&!active.contains(m.session)&&!protectedSessions.contains(m.session)&&(trimGlobal||trimSessions.contains(m.session)))
     .min(Comparator.comparing((Meta m)->ages.getOrDefault(m.session,Instant.EPOCH)).thenComparing(m->m.access).thenComparing(m->m.id)).orElse(null);
    if(victim==null)return;victim.state=State.DELETING;
   }
   if(!evict(victim,true,io))protectedSessions.add(victim.session);
  }
 }
 private boolean evict(Meta m,boolean protectAdmission,HistoryStore.Io io){
  if(!protectAdmission){deleteClaimed(m,io);return true;}
  var claim=admissions.tryEviction(m.session);
  if(claim.isEmpty()){restoreAvailable(m);return false;}
  try(var ignored=claim.get()){
   // The claim blocks new admission until this recheck and deletion have finished.
   boolean active;
   try{active=jdbc.queryForObject("select count(*) from agent_task where session_id=? and "+UNFINISHED,Long.class,m.session)>0;}
   catch(RuntimeException failure){restoreAvailable(m);throw failure;}
   if(active){restoreAvailable(m);return false;}
   deleteClaimed(m,io);return true;
  }
 }
 private void restoreAvailable(Meta m){synchronized(quota){m.state=m.truncated?State.TRUNCATED:State.AVAILABLE;}}
 private void deleteClaimed(Meta m,HistoryStore.Io io){
  persist(m,io);
  retry.execute(io,"ARTIFACT_STORAGE_FAILED",()->{try{io.check();Files.deleteIfExists(path(m,false));return true;}catch(IOException e){throw new UncheckedIOException(e);}});
  // Keep bytes reserved until the durable EVICTED/zero-byte update is confirmed.
  confirmState(m,State.EVICTED,0,io);
 }
 private void reconcile(){
  var io=new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(10));
  for(Meta m:List.copyOf(entries.values())){
   if(m.state==State.WRITING||m.state==State.WRITE_FAILED){
    try{Path p=path(m,false);Files.deleteIfExists(p.resolveSibling(p.getFileName()+".tmp"));Files.deleteIfExists(p);confirmState(m,State.WRITE_FAILED,0,io);}
    catch(IOException e){throw new ContextSessionException("ARTIFACT_STORAGE_FAILED",e);}
   }else if(m.state==State.DELETING){deleteClaimed(m,io);}
   else if(available(m)&&!Files.exists(path(m,false))){confirmState(m,State.MISSING,0,io);}
  }
 }
 public void deleteSession(String session,HistoryStore.Io io){while(!deleteSession(session,32,io)){io.check();}}
 public boolean deleteSession(String session,int maxFiles,HistoryStore.Io io){
  if(maxFiles<1||maxFiles>32)throw new IllegalArgumentException("maxFiles must be 1..32");
  // Include zero-byte live files, whose metadata need not occupy the resident quota index.
  var stored=jdbc.query("select "+COLUMNS+" from agent_artifact where session_id=? and ("+LIVE+" or state in ('AVAILABLE','TRUNCATED')) order by id limit ?",(rs,row)->metadata(rs),session,maxFiles);
  List<Meta> pending=new ArrayList<>();
  synchronized(quota){
   if(entries.values().stream().anyMatch(m->m.session.equals(session)&&(m.pins>0||m.state==State.WRITING)))throw fail("SESSION_BUSY");
   for(Meta candidate:stored){Meta m=entries.getOrDefault(candidate.id,candidate);if(m.pins>0||m.state==State.WRITING)throw fail("SESSION_BUSY");m.state=State.DELETING;entries.put(m.id,m);pending.add(m);}
  }
  for(Meta m:pending){io.check();Path file=path(m,false);try{Files.deleteIfExists(file.resolveSibling(file.getFileName()+".tmp"));}catch(IOException e){throw new UncheckedIOException(e);}deleteClaimed(m,io);}
  return jdbc.queryForObject("select count(*) from agent_artifact where session_id=? and ("+LIVE+" or state in ('AVAILABLE','TRUNCATED'))",Long.class,session)==0;
 }
 public void forgetSession(String session){synchronized(quota){if(entries.values().stream().anyMatch(m->m.session.equals(session)&&(m.pins>0||m.bytes>0||m.state==State.WRITING||m.state==State.DELETING)))throw fail("SESSION_BUSY");entries.values().removeIf(m->m.session.equals(session));}}
 private void persist(Meta m,HistoryStore.Io io){State state;long bytes;synchronized(quota){state=m.state;bytes=m.bytes;}writeMetadata(m,state,bytes,io);}
 private void confirmState(Meta m,State state,long bytes,HistoryStore.Io io){
  writeMetadata(m,state,bytes,io);
  synchronized(quota){m.state=state;m.bytes=bytes;forgetSettled(m);}
 }
 private void writeMetadata(Meta m,State state,long bytes,HistoryStore.Io io){
  retry.execute(io,"ARTIFACT_STORAGE_FAILED",()->tx.execute(status->{int updated=jdbc.update("update agent_artifact set state=?,saved_bytes=?,sha256=?,last_accessed_at=?,deleted_at=? where id=?",state.name(),bytes,m.hash,m.access,state==State.EVICTED?Instant.now():null,m.id);if(updated!=1)throw fail("ARTIFACT_METADATA_MISSING");return true;}));
 }
 /** Caller holds quota. Pending operations remain resident even when their byte count is zero. */
 private void forgetSettled(Meta m){if(m.pins==0&&m.bytes==0&&m.state!=State.WRITING&&m.state!=State.DELETING)entries.remove(m.id,m);}
 private void owned(HistoryStore.Scope s){if(!s.sessionId().matches("[A-Za-z0-9_-]{1,36}"))throw fail("ARTIFACT_NOT_FOUND");if(jdbc.queryForObject("select count(*) from agent_task t join agent_session s on s.id=t.session_id left join agent_session_context c on c.session_id=s.id where t.id=? and t.session_id=? and t.user_id=? and s.user_id=? and c.deleted_at is null",Long.class,s.runId(),s.sessionId(),s.userId(),s.userId())!=1)throw fail("ARTIFACT_NOT_FOUND");}
 private Detail detail(HistoryStore.Scope scope,String id){
  var rows=jdbc.query("select "+COLUMNS+",preview from agent_artifact where id=? and owner_id=? and session_id=?",(rs,row)->new Detail(metadata(rs),rs.getString("preview")),id,scope.userId(),scope.sessionId());
  if(rows.isEmpty())throw fail("ARTIFACT_NOT_FOUND");return rows.get(0);
 }
 /** Caller holds quota; only active read/pin handles temporarily index a zero-byte file. */
 private Meta forUse(HistoryStore.Scope scope,Detail stored){
  Meta m=entries.get(stored.meta().id);
  if(m==null){m=stored.meta();if(available(m)){if(m.bytes>0)throw fail("ARTIFACT_UNAVAILABLE");}}
  checkOwner(scope,m);return m;
 }
 private static void checkOwner(HistoryStore.Scope scope,Meta m){if(!m.owner.equals(scope.userId())||!m.session.equals(scope.sessionId()))throw fail("ARTIFACT_NOT_FOUND");}
 private static Meta metadata(ResultSet rs)throws SQLException{
  Meta m=new Meta();m.id=rs.getString("id");m.owner=rs.getString("owner_id");m.session=rs.getString("session_id");m.run=rs.getString("run_id");m.kind=rs.getString("kind");m.state=State.valueOf(rs.getString("state"));m.path=rs.getString("relative_path");m.bytes=rs.getLong("saved_bytes");m.original=(Long)rs.getObject("original_bytes");m.hash=rs.getString("sha256");m.truncated=rs.getBoolean("truncated");m.redacted=rs.getBoolean("redacted");m.created=rs.getTimestamp("created_at").toInstant();m.access=rs.getTimestamp("last_accessed_at").toInstant();return m;
 }
 private long used(String session){return entries.values().stream().filter(m->session==null||m.session.equals(session)).map(m->m.bytes).reduce(0L,Math::addExact);}
 private boolean available(Meta m){return m.state==State.AVAILABLE||m.state==State.TRUNCATED;}
 private Map<String,Instant> lastUsed(){Map<String,Instant> map=new HashMap<>();jdbc.query("select session_id,last_used_at from agent_session_context",(org.springframework.jdbc.core.RowCallbackHandler)rs->{map.put(rs.getString(1),rs.getTimestamp(2).toInstant());});return map;}
 private Set<String> activeSessions(){return new HashSet<>(jdbc.queryForList("select distinct session_id from agent_task where "+UNFINISHED,String.class));}
 private Path path(Meta m,boolean create){try{Path p=root.resolve(m.path).normalize();if(!p.startsWith(root)||!p.getParent().equals(root.resolve(m.session))||!p.getFileName().toString().equals(m.id+".txt"))throw fail("ARTIFACT_PATH_INVALID");if(create)Files.createDirectories(p.getParent());if(Files.isSymbolicLink(p.getParent())||Files.isSymbolicLink(p)||(Files.exists(p.getParent())&&!p.getParent().toRealPath().startsWith(root))||!root.toRealPath().equals(root))throw fail("ARTIFACT_PATH_INVALID");return p;}catch(IOException e){throw new UncheckedIOException(e);}}
 private long textTokens(String text){
  // The artifact cap budgets content; the context estimator separately charges its message envelope.
  long envelope=estimator.estimateInput(List.of(com.agentflow.core.model.ModelMessage.user("")),List.of());
  long total=estimator.estimateInput(List.of(com.agentflow.core.model.ModelMessage.user(text)),List.of());
  return Math.max(0L,total-envelope);
 }
 private String preview(String text,int tokens){int cap=(int)Math.min(text.length(),(long)tokens*16);if(cap>0&&Character.isHighSurrogate(text.charAt(cap-1)))cap--;text=text.substring(0,cap);if(textTokens(text)<=tokens)return text;int low=0,high=text.length();while(low<high){int mid=(low+high+1)/2;if(textTokens(text.substring(0,mid))<=tokens)low=mid;else high=mid-1;}if(low>0&&Character.isHighSurrogate(text.charAt(low-1)))low--;return text.substring(0,low);}
 private static String redact(String text){return text.replaceAll("(?i)(api[-_ ]?key|token|secret|password)(\\s*[:=]\\s*)[^,;\\s\"'}]+","$1$2[redacted]").replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+","Bearer [redacted]");}
 private static IllegalStateException fail(String code){return new IllegalStateException(code);}
}
