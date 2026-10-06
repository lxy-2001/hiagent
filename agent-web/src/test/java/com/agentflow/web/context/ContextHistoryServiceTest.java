package com.agentflow.web.context;

import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.context.TokenEstimator;
import com.agentflow.llm.OpenAiResponsesAgentModelClient;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.web.agent.*;
import com.agentflow.web.autoconfigure.AgentWebAutoConfiguration;
import com.agentflow.web.run.RunCoordinator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes=ContextHistoryServiceTest.Application.class, webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.datasource.url=jdbc:h2:mem:history-api;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false"})
class ContextHistoryServiceTest {
    static final Instant CREATED=Instant.parse("2026-10-07T01:00:00Z");
    static final Instant TOUCHED=CREATED.plusSeconds(60);
    @Autowired ContextHistoryService service;
    @Autowired EntityManager manager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void pagesAtFixedCutoffWithBoundedRedactedPreviewsAndToolMetadata() {
        String session="api-pages";
        seed(session, List.of(ModelMessage.user("apiKey=very-secret-value"),
                ModelMessage.assistantToolCall(new ToolCall("c1", "read-file", new ToolArguments(Map.of("path","hello.txt")))),
                ModelMessage.assistant("large ".repeat(1500))));
        var first=service.messages("owner",session,"0",null,2);
        assertThat(first.untilSequence()).isEqualTo("3");
        assertThat(first.nextAfterSequence()).isEqualTo("2");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items().get(0).preview()).contains("[redacted]").doesNotContain("very-secret-value");
        assertThat(first.items().get(0).redacted()).isTrue();
        assertThat(first.items().get(1).preview()).contains("hello.txt");
        assertThat(first.items().get(1).toolName()).isEqualTo("read-file");
        var last=service.messages("owner",session,"2",first.untilSequence(),2);
        assertThat(last.items()).hasSize(1);
        assertThat(last.items().get(0).preview()).hasSize(4096);
        assertThat(last.items().get(0).truncated()).isTrue();
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextAfterSequence()).isNull();
        assertThat(mapper.writeValueAsBytes(last)).hasSizeLessThan(256*1024);
        assertThat(lastUsed(session)).isEqualTo(CREATED);
        service.touch("owner",session);
        assertThat(lastUsed(session)).isEqualTo(TOUCHED);
    }

    @Test void rejectsOtherOwnerDeletedSessionsAndInvalidCursors() {
        seed("api-scope",List.of(ModelMessage.user("hello")));
        assertThatThrownBy(()->service.messages("other","api-scope","0",null,20)).isInstanceOf(RunCoordinator.RunNotFoundException.class);
        assertThatThrownBy(()->service.messages("owner","api-scope","9223372036854775808","1",20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.messages("owner","api-scope","1",null,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.messages("owner","api-scope","0","2",20)).isInstanceOf(IllegalArgumentException.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx->manager.find(SessionContextEntity.class,"api-scope").delete(TOUCHED));
        assertThatThrownBy(()->service.touch("owner","api-scope")).isInstanceOf(RunCoordinator.RunNotFoundException.class);
    }

    @Test void requestLedgerDeduplicatesRealUsageAndReportsNewerFailedCompaction() {
        String session="api-ledger";
        seed(session,List.of(ModelMessage.user("hello")));
        var oldCheckpoint=new HistoryStore.Checkpoint("ledger-checkpoint",2,null,1,"earlier summary",10,200,20,2,
                "compact-old","v1","profile",CREATED);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            manager.persist(new ContextCheckpointEntity(session,oldCheckpoint));
            manager.find(SessionContextEntity.class,session).checkpoint(oldCheckpoint.id());
        });
        jdbc.execute("""
                create table if not exists agent_context_model_call (
                    id varchar(36) primary key, session_id varchar(36) not null, run_id varchar(36) not null,
                    request_key varchar(128) not null, purpose varchar(32) not null, status varchar(32) not null,
                    provider varchar(100) not null, model varchar(100) not null, input_tokens bigint null,
                    output_tokens bigint null, cached_input_tokens bigint null, reasoning_tokens bigint null,
                    usage_source varchar(32) not null, estimated_input_tokens bigint not null,
                    output_reserve_tokens bigint not null, safety_tokens bigint not null, estimate_source varchar(100) not null,
                    source_history_tokens bigint null, retained_history_tokens bigint null, failure_code varchar(128) null,
                    started_at timestamp(6) not null, finished_at timestamp(6) null,
                    constraint uq_ledger_test_request unique(run_id,request_key))
                """);
        var estimator=new TokenEstimator() {
            public long estimateInput(List<ModelMessage> messages,List<com.agentflow.core.tool.ToolDefinition> tools) { return 0; }
            public String version() { return "fixture-encoding-v1"; }
        };
        var ledger=new ModelCallLedger(jdbc,transactions,new BoundedPersistenceRetry(Clock.systemUTC()),estimator,"gpt-6.1-sol");
        service.configureLedger(ledger);
        var scope=new HistoryStore.Scope("owner",session,"run-"+session);
        var io=new HistoryStore.Io(()->false,Instant.now().plusSeconds(60));
        var beforeRequest=service.context("owner",session);
        assertThat(beforeRequest.outputReserveTokens()).isEqualTo(32768);
        assertThat(beforeRequest.safetyTokens()).isEqualTo(8192);
        ledger.observe(new ContextSessionService.Observation(scope,"decision-1","MODEL_DECISION",112233,100,512,null,null,null),io);
        var decision=new OpenAiResponsesAgentModelClient.CallReport(scope,"decision-1","MODEL_DECISION","gpt-6.1-sol","SUCCEEDED",
                new OpenAiResponsesAgentModelClient.Counters(100L,20L,60L,5L),null,CREATED.plusSeconds(10),CREATED.plusSeconds(11));
        ledger.record(decision,io); ledger.record(decision,io);
        var completed=service.context("owner",session);
        assertThat(completed.usageComplete()).isTrue();
        assertThat(completed.actualUsage()).isEqualTo(new ContextHistoryService.Usage(100L,20L,60L,5L));
        assertThat(completed.estimatedInputTokens()).isEqualTo(112233L);
        assertThat(completed.estimateSource()).isEqualTo("fixture-encoding-v1");
        assertThat(completed.outputReserveTokens()).isEqualTo(100);
        assertThat(completed.safetyTokens()).isEqualTo(512);

        var failed=new OpenAiResponsesAgentModelClient.CallReport(scope,"compact-failed","COMPACTION","gpt-6.1-sol","FAILED",
                new OpenAiResponsesAgentModelClient.Counters(null,2L,null,1L),"MODEL_ERROR",CREATED.plusSeconds(20),CREATED.plusSeconds(21));
        ledger.record(failed,io); ledger.record(failed,io);
        ledger.observe(new ContextSessionService.Observation(scope,"compact-failed","COMPACTION",123456,8192,8192,100000L,null,"CONTEXT_COMPACTION_FAILED"),io);
        var partial=service.context("owner",session);
        assertThat(partial.usageComplete()).isFalse();
        assertThat(partial.actualUsage()).isNull();
        assertThat(partial.knownUsage()).isEqualTo(new ContextHistoryService.Usage(100L,22L,60L,6L));
        assertThat(partial.estimatedInputTokens()).isEqualTo(123456L);
        assertThat(partial.checkpointId()).isEqualTo("ledger-checkpoint");
        assertThat(partial.lastCompaction().requestKey()).isEqualTo("compact-failed");
        assertThat(partial.lastCompaction().status()).isEqualTo("FAILED");
        assertThat(partial.lastCompaction().failureCode()).isEqualTo("CONTEXT_COMPACTION_FAILED");
        assertThat(partial.lastCompaction().checkpointId()).isNull();
        assertThat(partial.lastCompaction().sourceHistoryTokens()).isEqualTo(100000);
        assertThat(jdbc.queryForObject("select count(*) from agent_context_model_call where session_id=?",Long.class,session)).isEqualTo(2);
        assertThatThrownBy(()->ledger.record(new OpenAiResponsesAgentModelClient.CallReport(
                new HistoryStore.Scope("other",session,"run-"+session),"decision-1","MODEL_DECISION","gpt-6.1-sol","SUCCEEDED",
                decision.counters(),null,decision.startedAt(),decision.finishedAt()),io)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.context("other",session)).isInstanceOf(RunCoordinator.RunNotFoundException.class);
    }

    private void seed(String session,List<ModelMessage> messages) {
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            manager.persist(new AgentSessionEntity(session,"owner","title",CREATED));
            manager.persist(new AgentTaskEntity("run-"+session,session,"owner","hello","SUCCEEDED",CREATED,1,false));
            var context=new SessionContextEntity(session,CREATED); context.appended(messages.size()); manager.persist(context);
            for(int i=0;i<messages.size();i++) {
                var message=messages.get(i); var call=message.toolCall();
                var payload=new LinkedHashMap<String,Object>();
                payload.put("role",message.role()); payload.put("content",message.content()); payload.put("name",message.name());
                payload.put("toolCallId",message.toolCallId()); payload.put("callId",call==null?null:call.callId());
                payload.put("toolName",call==null?null:call.name()); payload.put("arguments",call==null?null:call.arguments().values());
                String body=mapper.writeValueAsString(payload);
                var item=new HistoryStore.Item(call==null?HistoryStore.Kind.USER_MESSAGE:HistoryStore.Kind.TOOL_CALL,message,CREATED,"d1",message.toolCallId(),null,null,"private-adapter-ref");
                manager.persist(new HistoryRecordEntity(UUID.randomUUID().toString(),session,"run-"+session,i+1,item,body,body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            }
        });
    }
    private Instant lastUsed(String session) { return new TransactionTemplate(transactions).execute(tx->manager.find(SessionContextEntity.class,session).getLastUsedAt()); }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude={AgentWebAutoConfiguration.class,com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration.class})
    @EntityScan(basePackageClasses={AgentSessionEntity.class,SessionContextEntity.class})
    @EnableJpaRepositories(basePackageClasses=AgentSessionRepository.class)
    static class Application {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean ContextHistoryService service(EntityManager manager,AgentSessionRepository sessions,Environment env,ObjectMapper mapper) {
            return new ContextHistoryService(manager,sessions,new ContextTextPolicy(),mock(RunCoordinator.class),env,mapper,Clock.fixed(TOUCHED,ZoneOffset.UTC));
        }
    }
}
