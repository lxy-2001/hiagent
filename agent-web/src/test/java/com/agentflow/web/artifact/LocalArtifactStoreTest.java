package com.agentflow.web.artifact;

import com.agentflow.core.artifact.ArtifactStore;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.context.Utf8TokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.Path;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class LocalArtifactStoreTest {
 @TempDir Path directory;
 private HistoryStore.Io io(){return new HistoryStore.Io(CancellationSignal.NONE,Instant.now().plusSeconds(30));}
 @Test void boundsUtf8ContentAndQuotaWithoutDeletingPinnedOrActiveSessions() throws Exception {
  var data=new DriverManagerDataSource("jdbc:h2:mem:artifact;DB_CLOSE_DELAY=-1","sa","");
  var jdbc=new JdbcTemplate(data);
  jdbc.execute("drop all objects");
  jdbc.execute("create table agent_session(id varchar primary key,user_id varchar)");
  jdbc.execute("create table agent_task(id varchar primary key,session_id varchar,user_id varchar,status varchar)");
  jdbc.execute("create table agent_session_context(session_id varchar primary key,last_used_at timestamp, deleted_at timestamp)");
  jdbc.execute("create table agent_artifact(id varchar primary key,owner_id varchar,session_id varchar,run_id varchar,kind varchar,state varchar,relative_path varchar,saved_bytes bigint,original_bytes bigint,sha256 varchar,preview clob,truncated boolean,redacted boolean,created_at timestamp,last_accessed_at timestamp,deleted_at timestamp)");
  for(var s:java.util.List.of("s1","s2","s3","s4")){jdbc.update("insert into agent_session values(?,?)",s,"u");jdbc.update("insert into agent_task values(?,?,?,?)","r"+s,s,"u","SUCCEEDED");jdbc.update("insert into agent_session_context values(?,current_timestamp,null)",s);}
  var store=new LocalArtifactStore(jdbc,new DataSourceTransactionManager(data),directory,new Utf8TokenEstimator(),new LocalArtifactStore.Limits(64,100,70,180,120));
  var scope=new HistoryStore.Scope("u","s1","rs1"); var other=new HistoryStore.Scope("u","s2","rs2");
  var a=store.save(scope,"TOOL_OUTPUT","中文🙂".repeat(10),io());
  assertThat(a.state()).isEqualTo(ArtifactStore.State.TRUNCATED);assertThat(a.savedBytes()).isLessThanOrEqualTo(64);assertThat(a.truncated()).isTrue();
  var first=store.read(scope,a.id(),0,9,8192,io());
  assertThat(first.content()).isEqualTo("中文");
  assertThatThrownBy(()->store.read(scope,a.id(),1,9,8192,io())).hasMessageContaining("INVALID_ARTIFACT_RANGE");
  assertThatThrownBy(()->store.get(other,a.id(),io())).hasMessageContaining("ARTIFACT_NOT_FOUND");
  try(var pin=store.pin(scope,a.id())){assertThatThrownBy(()->store.save(scope,"TOOL_OUTPUT","b".repeat(60),io())).hasMessageContaining("ARTIFACT_STORAGE_FAILED");}
  var b=store.save(scope,"TOOL_OUTPUT","b".repeat(60),io());
  assertThat(store.get(scope,a.id(),io()).state()).isEqualTo(ArtifactStore.State.EVICTED);
  java.nio.file.Files.delete(directory.resolve("artifacts/s1/"+b.id()+".txt"));
  java.nio.file.Files.delete(directory.resolve("artifacts/s1"));
  assertThatThrownBy(()->store.read(scope,b.id(),0,32,8192,io())).hasMessageContaining("ARTIFACT_MISSING");
  assertThat(store.get(scope,b.id(),io()).state()).isEqualTo(ArtifactStore.State.MISSING);
  // Global cleanup uses persisted session recency, protects active sessions, and reaches low water.
  var global=new LocalArtifactStore(jdbc,new DataSourceTransactionManager(data),directory,new Utf8TokenEstimator(),new LocalArtifactStore.Limits(64,100,70,200,160));
  jdbc.update("update agent_session_context set last_used_at=? where session_id=?",Instant.parse("2020-01-01T00:00:00Z"),"s3");
  jdbc.update("update agent_session_context set last_used_at=? where session_id=?",Instant.parse("2021-01-01T00:00:00Z"),"s1");
  jdbc.update("update agent_session_context set last_used_at=? where session_id=?",Instant.parse("2022-01-01T00:00:00Z"),"s2");
  jdbc.update("update agent_task set status='RUNNING' where session_id='s3'");
  var s3=new HistoryStore.Scope("u","s3","rs3");
  var s4=new HistoryStore.Scope("u","s4","rs4");
  var a1=global.save(scope,"TOOL_OUTPUT","x".repeat(60),io());
  var a2=global.save(other,"TOOL_OUTPUT","x".repeat(60),io());
  var active=global.save(s3,"TOOL_OUTPUT","x".repeat(60),io());
  global.save(s4,"TOOL_OUTPUT","x".repeat(60),io());
  assertThat(global.get(s3,active.id(),io()).state()).isEqualTo(ArtifactStore.State.AVAILABLE);
  assertThat(global.get(scope,a1.id(),io()).state()).isEqualTo(ArtifactStore.State.EVICTED);
  assertThat(global.get(other,a2.id(),io()).state()).isEqualTo(ArtifactStore.State.EVICTED);
  assertThat(jdbc.queryForObject("select sum(saved_bytes) from agent_artifact",Long.class)).isLessThanOrEqualTo(160L);
  assertThatThrownBy(()->store.save(scope,"../escape","x",io())).hasMessageContaining("INVALID_ARTIFACT_KIND");
  var sourcePrefix=store.save(scope,"TOOL_OUTPUT","short [redacted]",true,io());
  assertThat(sourcePrefix.state()).isEqualTo(ArtifactStore.State.TRUNCATED);
  assertThat(sourcePrefix.originalBytes()).isNull();
  assertThat(sourcePrefix.truncated()).isTrue();
  assertThat(sourcePrefix.redacted()).isTrue();
  assertThatThrownBy(() -> new LocalArtifactStore.Limits(64,100,100,200,160)).isInstanceOf(IllegalArgumentException.class);
  var smaller=new LocalArtifactStore(jdbc,new DataSourceTransactionManager(data),directory,new Utf8TokenEstimator(),new LocalArtifactStore.Limits(64,90,70,130,100),4096,8,new com.agentflow.web.context.BoundedPersistenceRetry(java.time.Clock.systemUTC()));
  assertThat(smaller.read(s3,active.id(),0,64,8192,io()).content().length()).isLessThanOrEqualTo(8);
  assertThat(jdbc.queryForObject("select sum(saved_bytes) from agent_artifact",Long.class)).isLessThanOrEqualTo(100L);
  assertThat(smaller.get(s3,active.id(),io()).state()).isEqualTo(ArtifactStore.State.AVAILABLE);
 }
 @Test void keepsEvictedPreviewsInDatabaseWithoutRetainingAllMetadataInHeap() throws Exception {
  var fixture=fixture(new JdbcTemplate());
  var store=new LocalArtifactStore(fixture.jdbc(),fixture.tx(),directory,new Utf8TokenEstimator(),new LocalArtifactStore.Limits(64,100,70,180,120));
  var scope=new HistoryStore.Scope("u","s1","rs1");
  var oldest=store.save(scope,"TOOL_OUTPUT","first".repeat(12),io());
  for(int i=0;i<20;i++)store.save(scope,"TOOL_OUTPUT","x".repeat(60),io());
  assertThat(residentEntries(store)).isLessThanOrEqualTo(1);
  var reopened=new LocalArtifactStore(fixture.jdbc(),fixture.tx(),directory,new Utf8TokenEstimator(),new LocalArtifactStore.Limits(64,100,70,180,120));
  assertThat(residentEntries(reopened)).isLessThanOrEqualTo(1);
  var tombstone=reopened.get(scope,oldest.id(),io());
  assertThat(tombstone.state()).isEqualTo(ArtifactStore.State.EVICTED);
  assertThat(tombstone.preview()).isEqualTo("first".repeat(12));
  assertThat(residentEntries(reopened)).isLessThanOrEqualTo(1);
 }
 @Test void rechecksNewlyActiveSessionBeforeGlobalEviction() {
  var activateAfterSnapshot=new java.util.concurrent.atomic.AtomicBoolean();
  var jdbc=new JdbcTemplate(){
   @Override public <T> java.util.List<T> queryForList(String sql,Class<T> elementType){
    var rows=super.queryForList(sql,elementType);
    if(sql.startsWith("select distinct session_id from agent_task")&&activateAfterSnapshot.compareAndSet(true,false))
     update("update agent_task set status='QUEUED' where session_id='s1'");
    return rows;
   }
  };
  var fixture=fixture(jdbc);
  var store=new LocalArtifactStore(jdbc,fixture.tx(),directory,new Utf8TokenEstimator(),new LocalArtifactStore.Limits(64,100,70,180,120));
  var first=new HistoryStore.Scope("u","s1","rs1");
  var second=new HistoryStore.Scope("u","s2","rs2");
  var third=new HistoryStore.Scope("u","s3","rs3");
  jdbc.update("update agent_session_context set last_used_at=? where session_id='s1'",Instant.parse("2020-01-01T00:00:00Z"));
  var protectedRef=store.save(first,"TOOL_OUTPUT","a".repeat(60),io());
  var idle=store.save(second,"TOOL_OUTPUT","b".repeat(60),io());
  store.save(third,"TOOL_OUTPUT","c".repeat(60),io());
  activateAfterSnapshot.set(true);
  store.save(new HistoryStore.Scope("u","s4","rs4"),"TOOL_OUTPUT","d".repeat(60),io());
  assertThat(store.get(first,protectedRef.id(),io()).state()).isEqualTo(ArtifactStore.State.AVAILABLE);
  assertThat(store.get(second,idle.id(),io()).state()).isEqualTo(ArtifactStore.State.EVICTED);
 }
 private int residentEntries(LocalArtifactStore store)throws Exception{
  var field=LocalArtifactStore.class.getDeclaredField("entries");field.setAccessible(true);
  return ((java.util.Map<?,?>)field.get(store)).size();
 }
 private record Fixture(JdbcTemplate jdbc,DataSourceTransactionManager tx){}
 private Fixture fixture(JdbcTemplate jdbc){
  var data=new DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
  jdbc.setDataSource(data);
  jdbc.execute("create table agent_session(id varchar primary key,user_id varchar)");
  jdbc.execute("create table agent_task(id varchar primary key,session_id varchar,user_id varchar,status varchar)");
  jdbc.execute("create table agent_session_context(session_id varchar primary key,last_used_at timestamp, deleted_at timestamp)");
  jdbc.execute("create table agent_artifact(id varchar primary key,owner_id varchar,session_id varchar,run_id varchar,kind varchar,state varchar,relative_path varchar,saved_bytes bigint,original_bytes bigint,sha256 varchar,preview clob,truncated boolean,redacted boolean,created_at timestamp,last_accessed_at timestamp,deleted_at timestamp)");
  for(var session:java.util.List.of("s1","s2","s3","s4")){
   jdbc.update("insert into agent_session values(?,?)",session,"u");
   jdbc.update("insert into agent_task values(?,?,?,?)","r"+session,session,"u","SUCCEEDED");
   jdbc.update("insert into agent_session_context values(?,current_timestamp,null)",session);
  }
  return new Fixture(jdbc,new DataSourceTransactionManager(data));
 }}
