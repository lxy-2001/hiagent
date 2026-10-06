package com.agentflow.web.autoconfigure;

import com.agentflow.core.artifact.ArtifactStore;
import com.agentflow.core.context.*;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.llm.*;
import com.agentflow.web.artifact.*;
import com.agentflow.web.context.*;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;

/** Explicit durable Harness adapters; the execution algorithm remains in Core. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="agentflow.harness.enabled", havingValue="true")
@EnableConfigurationProperties(HarnessStorageProperties.class)
@EnableScheduling
class HarnessContextConfiguration {
    @Bean @ConditionalOnMissingBean
    BoundedPersistenceRetry harnessPersistenceRetry(Environment env) {
        int attempts=env.getProperty("agentflow.harness.persistence.max-attempts",Integer.class,4);
        String delays=env.getProperty("agentflow.harness.persistence.backoff","200ms,500ms,1000ms");
        var backoff=delays.isBlank()?java.util.List.<java.time.Duration>of():java.util.Arrays.stream(delays.split(",")).map(String::trim).map(org.springframework.boot.convert.DurationStyle::detectAndParse).toList();
        return new BoundedPersistenceRetry(Clock.systemUTC(),delay->Thread.sleep(delay.toMillis()),attempts,backoff,
            org.springframework.boot.convert.DurationStyle.detectAndParse(env.getProperty("agentflow.harness.persistence.retry-window","10s")));
    }

    @Bean @ConditionalOnMissingBean
    JpaResponsesContinuationStore jpaResponsesContinuationStore(JpaHistoryStore history, EntityManager em, ObjectMapper mapper) {
        return new JpaResponsesContinuationStore(history, em, mapper);
    }

    @Bean @Primary @ConditionalOnMissingBean(value=ResponsesContinuationStore.class, ignored=JpaResponsesContinuationStore.class)
    ResponsesContinuationStore responsesContinuationStore(JpaResponsesContinuationStore delegate, BoundedPersistenceRetry retry) {
        return new ResponsesContinuationStore() {
            public String stage(Scope scope,String key,tools.jackson.databind.JsonNode output,Long hint,HistoryStore.Io io) {
                return retry.execute(io,"HISTORY_PERSISTENCE_FAILED",()->delegate.stage(scope,key,output,hint,io));
            }
            public java.util.Optional<tools.jackson.databind.JsonNode> read(Scope scope,String ref,HistoryStore.Io io) {
                return retry.execute(io,"HISTORY_PERSISTENCE_FAILED",()->delegate.read(scope,ref,io));
            }
            public void prune(Scope scope,HistoryStore.Io io) {
                retry.execute(io,"HISTORY_PERSISTENCE_FAILED",()->{delegate.prune(scope,io);return true;});
            }
        };
    }

    @Bean @ConditionalOnMissingBean
    ModelCallLedger modelCallLedger(JdbcTemplate jdbc,PlatformTransactionManager tx,BoundedPersistenceRetry retry,
                                   TokenEstimator estimator,Environment env) {
        return new ModelCallLedger(jdbc,tx,retry,capacity(estimator,jdbc,env),model(env));
    }

    @Bean @ConditionalOnMissingBean
    ResponsesRunSupport responsesRunSupport(ObjectProvider<ContextSessionService> sessions,ModelCallLedger ledger) {
        return new ResponsesRunSupport() {
            public HistoryStore.Io io(AgentModelRequest request) { return sessions.getObject().io(request.taskId()); }
            public void record(OpenAiResponsesAgentModelClient.CallReport report) { ledger.record(report,ContextSessionService.factIo()); }
        };
    }

    @Bean @ConditionalOnMissingBean
    ContextCompactor.Limits harnessContextLimits(Environment env) {
        double ratio=env.getProperty("agentflow.harness.context.compact-ratio",Double.class,.80);
        if(ratio<.80||ratio>.85)throw new IllegalArgumentException("compact-ratio must be between .80 and .85");
        return new ContextCompactor.Limits(env.getProperty("agentflow.harness.context.window-tokens",Long.class,272000L),ratio,
            env.getProperty("agentflow.harness.context.output-tokens",Integer.class,32768),
            env.getProperty("agentflow.harness.context.safety-tokens",Integer.class,8192),
            env.getProperty("agentflow.harness.context.compacted-history-ratio",Double.class,.20),
            env.getProperty("agentflow.harness.context.summary-tokens",Integer.class,8192),
            env.getProperty("agentflow.harness.context.recent-tokens",Integer.class,32768));
    }

    @Bean @ConditionalOnMissingBean
    ContextCompactor contextCompactor(CompactionModel model,TokenEstimator estimator,JdbcTemplate jdbc,Environment env,ObjectMapper mapper) {
        return new ContextCompactor(model,capacity(estimator,jdbc,env),CompactionPrompt.load(),text->{
            try { return CompactionPrompt.validFields(mapper.readValue(text,Map.class)); }
            catch(RuntimeException failure) { return false; }
        });
    }

    @Bean @ConditionalOnMissingBean(ArtifactStore.class)
    LocalArtifactStore localArtifactStore(JdbcTemplate jdbc,PlatformTransactionManager tx,HarnessStorageProperties storage,
                                         TokenEstimator estimator,Environment env,BoundedPersistenceRetry retry) {
        var defaults=LocalArtifactStore.Limits.defaults();
        var limits=new LocalArtifactStore.Limits(env.getProperty("agentflow.harness.artifact.max-file-bytes",Long.class,defaults.fileBytes()),
            env.getProperty("agentflow.harness.artifact.session-max-bytes",Long.class,defaults.sessionBytes()),
            env.getProperty("agentflow.harness.artifact.session-target-bytes",Long.class,defaults.sessionTarget()),
            env.getProperty("agentflow.harness.artifact.total-max-bytes",Long.class,defaults.totalBytes()),
            env.getProperty("agentflow.harness.artifact.total-target-bytes",Long.class,defaults.totalTarget()));
        return new LocalArtifactStore(jdbc,tx,storage.getDataRoot(),estimator,limits,
            env.getProperty("agentflow.harness.artifact.preview-tokens",Integer.class,4096),
            env.getProperty("agentflow.harness.artifact.read-tokens",Integer.class,8192),retry);
    }

    @Bean @ConditionalOnBean(LocalArtifactStore.class) @ConditionalOnMissingBean
    ArtifactMaintenance artifactMaintenance(JdbcTemplate jdbc,PlatformTransactionManager tx,LocalArtifactStore store,BoundedPersistenceRetry retry) {
        return new ArtifactMaintenance(jdbc,tx,store,Clock.systemUTC(),retry);
    }

    @Bean @ConditionalOnMissingBean
    ArtifactController artifactController(JdbcTemplate jdbc,ArtifactStore store,com.agentflow.web.run.RunCoordinator coordinator) {
        return new ArtifactController(jdbc,store,coordinator,Clock.systemUTC());
    }

    @Bean @ConditionalOnMissingBean
    OrphanCallRecovery orphanCallRecovery(JpaHistoryStore history,BoundedPersistenceRetry retry) {
        return new OrphanCallRecovery(history,retry,Clock.systemUTC());
    }

    @Bean @ConditionalOnMissingBean
    ContextSessionService contextSessionService(JpaHistoryStore store,BoundedPersistenceRetry retry,TokenEstimator estimator,
            JdbcTemplate jdbc,Environment env,ContextCompactor compactor,ContextCompactor.Limits limits,ArtifactStore artifacts,
            ModelCallLedger ledger,OrphanCallRecovery recovery) {
        var cache=new ContextSessionService.CacheLimits(env.getProperty("agentflow.harness.context.cached-sessions",Integer.class,4),
            env.getProperty("agentflow.harness.context.session-memory-bytes",Long.class,16777216L),
            env.getProperty("agentflow.harness.context.total-memory-bytes",Long.class,67108864L));
        String profile=profileHash("openai-responses:"+model(env)+":"+limits+":"+estimator.version());
        return new ContextSessionService(retry.history(store),capacity(estimator,jdbc,env),
            new ContextPolicy(ContextPolicy.DEFAULT_SYSTEM,"harness-v1",limits.windowTokens()),compactor,limits,artifacts,profile,
            observation->ledger.observe(observation,ContextSessionService.factIo()),cache,recovery::recover);
    }

    @Bean
    SmartInitializingSingleton harnessRequiredTools(ToolRegistry registry) {
        return ()->{if(registry.enabledDefinitions().stream().noneMatch(t->"read-artifact".equals(t.name())))
            throw new IllegalStateException("Harness ToolRegistry requires read-artifact");};
    }

    private static String profileHash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static TokenEstimator capacity(TokenEstimator visible,JdbcTemplate jdbc,Environment env) {
        return new ContinuationTokenEstimator(visible,jdbc,"openai-responses",model(env));
    }
    private static String model(Environment env) { return env.getProperty("agentflow.harness.model.name","gpt-6.1-sol"); }
}
