package com.agentflow.demo;

import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.context.Utf8TokenEstimator;
import com.agentflow.web.artifact.*;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.web.agent.*;
import com.agentflow.web.auth.*;
import com.agentflow.web.config.SecurityConfig;
import com.agentflow.web.context.*;
import com.agentflow.web.run.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextHistoryApiTest {
    @TempDir Path root;
    @Test void exposesCommittedHistoryAndContextWithJwtOwnershipAndFixedPagination() throws Exception {
        try (var app = start(root)) {
            seed(app, "session-api");
            String owner = app.getBean(JwtService.class).issueAccessToken("owner", "owner", List.of("USER")).value();
            String other = app.getBean(JwtService.class).issueAccessToken("other", "other", List.of("USER")).value();
            var page = request(app,"GET","/api/agent/sessions/session-api/messages?limit=1",owner,null);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.headers().firstValue("cache-control")).contains("no-store");
            var body = app.getBean(ObjectMapper.class).readTree(page.body());
            assertThat(body.get("untilSequence").asText()).isEqualTo("2");
            assertThat(body.get("nextAfterSequence").asText()).isEqualTo("1");
            assertThat(body.get("items").get(0).get("preview").asText()).isEqualTo("hello");
            assertThat(page.body()).doesNotContain("adapterState");
            var last = request(app,"GET","/api/agent/sessions/session-api/messages?afterSequence=1&untilSequence=2",owner,null);
            assertThat(last.statusCode()).isEqualTo(200);
            assertThat(app.getBean(ObjectMapper.class).readTree(last.body()).get("hasMore").asBoolean()).isFalse();
            var context = request(app,"GET","/api/agent/sessions/session-api/context",owner,null);
            assertThat(context.statusCode()).isEqualTo(200);
            assertThat(context.body()).contains("272000");
            var touch = request(app,"POST","/api/agent/sessions/session-api/touch",owner,null);
            assertThat(touch.statusCode()).isEqualTo(204);
            assertThat(touch.body()).isEmpty();
            assertThat(touch.headers().firstValue("cache-control")).contains("no-store");
            assertThat(request(app,"GET","/api/agent/sessions/session-api/messages",null,null).statusCode()).isEqualTo(401);
            assertThat(request(app,"GET","/api/agent/sessions/session-api/messages",other,null).statusCode()).isEqualTo(404);
            assertThat(request(app,"GET","/api/agent/sessions/missing/context",owner,null).statusCode()).isEqualTo(404);
            assertThat(request(app,"GET","/api/agent/sessions/session-api/messages?afterSequence=1",owner,null).statusCode()).isEqualTo(400);
            assertThat(request(app,"GET","/api/agent/sessions/session-api/messages?limit=101",owner,null).statusCode()).isEqualTo(400);
            app.getBean(JdbcTemplate.class).update("update agent_task set status='RUNNING' where id='run-api'");
            when(app.getBean(RunCoordinator.class).getOwned("owner","run-api"))
                    .thenThrow(new RunCoordinator.RunUnavailableException("PERSISTENCE_UNAVAILABLE","run-api"));
            var unavailable = request(app,"GET","/api/agent/sessions/session-api/context",owner,null);
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(unavailable.body()).contains("PERSISTENCE_UNAVAILABLE");
        }
    }
    @Test void artifactRangesPreserveUtf8AndHideUnavailableDetailsFromOtherUsers() throws Exception {
        try (var app = start(root)) {
            seed(app, "session-api");
            String owner = app.getBean(JwtService.class).issueAccessToken("owner", "owner", List.of("USER")).value();
            String other = app.getBean(JwtService.class).issueAccessToken("other", "other", List.of("USER")).value();
            var scope = new HistoryStore.Scope("owner", "session-api", "run-api");
            var artifact = app.getBean(LocalArtifactStore.class).save(scope, "TOOL_OUTPUT", "a中🙂z", io());
            String path = "/api/agent/artifacts/" + artifact.id();
            var range = request(app, "GET", path + "?offsetBytes=4&maxBytes=7", owner, null);
            assertThat(range.statusCode()).isEqualTo(200);
            assertThat(range.headers().firstValue("cache-control")).contains("no-store");
            var body = app.getBean(ObjectMapper.class).readTree(range.body());
            assertThat(body.get("content").asText()).isEqualTo("🙂z");
            assertThat(body.get("actualOffsetBytes").asLong()).isEqualTo(4);
            assertThat(body.get("nextOffsetBytes").asLong()).isEqualTo(9);
            assertThat(body.get("eof").asBoolean()).isTrue();
            assertThat(body.get("savedBytes").asLong()).isEqualTo(9);
            assertThat(range.body()).doesNotContain("relativePath", "adapterState", "owner_id");
            assertThat(request(app, "GET", path, null, null).statusCode()).isEqualTo(401);
            assertThat(request(app, "GET", path, other, null).statusCode()).isEqualTo(404);
            assertThat(request(app, "GET", path + "?offsetBytes=-1", owner, null).statusCode()).isEqualTo(400);
            assertThat(request(app, "GET", path + "?offsetBytes=2", owner, null).statusCode()).isEqualTo(400);
            assertThat(request(app, "GET", path + "?maxBytes=65537", owner, null).statusCode()).isEqualTo(400);
            String relative = app.getBean(JdbcTemplate.class).queryForObject("select relative_path from agent_artifact where id=?", String.class, artifact.id());
            Files.delete(root.resolve("artifacts").resolve(relative));
            var missing = request(app, "GET", path, owner, null);
            assertThat(missing.statusCode()).isEqualTo(410);
            assertThat(missing.body()).contains("ARTIFACT_MISSING");
            assertThat(request(app, "GET", path, other, null).statusCode()).isEqualTo(404);
        }
    }

    @Test void deleteHidesIdleSessionThenBoundedlyRemovesItsOwnedDataAndBodies() throws Exception {
        try (var app = start(root)) {
            seed(app, "session-api");
            var jdbc = app.getBean(JdbcTemplate.class);
            String owner = app.getBean(JwtService.class).issueAccessToken("owner", "owner", List.of("USER")).value();
            String other = app.getBean(JwtService.class).issueAccessToken("other", "other", List.of("USER")).value();
            var artifact = app.getBean(LocalArtifactStore.class).save(new HistoryStore.Scope("owner", "session-api", "run-api"), "TOOL_OUTPUT", "saved result", io());
            String relative = jdbc.queryForObject("select relative_path from agent_artifact where id=?", String.class, artifact.id());
            Path body = root.resolve("artifacts").resolve(relative);
            String path = "/api/agent/sessions/session-api";
            assertThat(request(app, "DELETE", path, other, null).statusCode()).isEqualTo(404);
            assertThat(request(app, "DELETE", path, null, null).statusCode()).isEqualTo(401);
            jdbc.update("update agent_task set status='RUNNING' where id='run-api'");
            var busy = request(app, "DELETE", path, owner, null);
            assertThat(busy.statusCode()).isEqualTo(409);
            assertThat(busy.body()).contains("SESSION_BUSY");
            assertThat(Files.exists(body)).isTrue();
            jdbc.update("update agent_task set status='SUCCEEDED' where id='run-api'");
            var accepted = request(app, "DELETE", path, owner, null);
            assertThat(accepted.statusCode()).isEqualTo(202);
            assertThat(accepted.body()).contains("CLEANUP_PENDING", "session-api");
            assertThat(accepted.headers().firstValue("cache-control")).contains("no-store");
            assertThat(request(app, "DELETE", path, owner, null).statusCode()).isEqualTo(202);
            assertThat(request(app, "GET", path + "/messages", owner, null).statusCode()).isEqualTo(404);
            assertThat(request(app, "GET", "/api/agent/artifacts/" + artifact.id(), owner, null).statusCode()).isEqualTo(404);
            Class<?> maintenanceType = Class.forName("com.agentflow.web.artifact.ArtifactMaintenance");
            Object maintenance = app.getBean(maintenanceType);
            for (int pass = 0; pass < 8 && jdbc.queryForObject("select count(*) from agent_session where id='session-api'", Long.class) > 0; pass++) {
                maintenanceType.getMethod("maintainDeletedSessions").invoke(maintenance);
            }
            assertThat(jdbc.queryForObject("select count(*) from agent_session where id='session-api'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from agent_task where id='run-api'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from agent_history_record where session_id='session-api'", Long.class)).isZero();
            assertThat(Files.exists(body)).isFalse();
            assertThat(request(app, "DELETE", path, owner, null).statusCode()).isEqualTo(404);
        }
    }

    static HistoryStore.Io io() { return new HistoryStore.Io(() -> false, Instant.now().plusSeconds(10)); }
    static void seed(org.springframework.context.ConfigurableApplicationContext app,String session) {
        var em=app.getBean(EntityManager.class); var mapper=app.getBean(ObjectMapper.class); Instant now=Instant.now();
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
            em.persist(new AgentSessionEntity(session,"owner","title",now));
            em.persist(new AgentTaskEntity("run-api",session,"owner","hello","SUCCEEDED",now,1,false));
            var state=new SessionContextEntity(session,now); state.appended(2); em.persist(state);
            for(int i=1;i<=2;i++) {
                var message=ModelMessage.user(i==1?"hello":"world");
                String payload=mapper.writeValueAsString(Map.of("role","user","content",message.content()));
                em.persist(new HistoryRecordEntity(UUID.randomUUID().toString(),session,"run-api",i,
                        new HistoryStore.Item(HistoryStore.Kind.USER_MESSAGE,message,now,null,null,null,null,null),payload,payload.length()));
            }
        });
    }
    static org.springframework.context.ConfigurableApplicationContext start(Path root) {
        return new SpringApplicationBuilder(Application.class).profiles("local-harness")
                .properties("spring.main.banner-mode=off").run("--server.port=0",
                "--agentflow.harness.enabled=true", "--agentflow.harness.storage.data-root="+root.toString().replace('\\','/'),
                "--agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=1", "--spring.data.redis.timeout=100ms",
                "--spring.autoconfigure.exclude=com.agentflow.web.autoconfigure.AgentWebAutoConfiguration,com.agentflow.autoconfigure.AgentRuntimeAutoConfiguration,com.agentflow.llm.AgentLlmAutoConfiguration,com.agentflow.tool.AgentToolAutoConfiguration,com.agentflow.tool.ToolPolicyAutoConfiguration,com.agentflow.mcp.McpAutoConfiguration,com.agentflow.rag.AgentRagAutoConfiguration");
    }
    static HttpResponse<String> request(org.springframework.context.ConfigurableApplicationContext app,String method,String path,String token,String json) throws Exception {
        int port=((WebServerApplicationContext)app).getWebServer().getPort();
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5));
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(json!=null) builder.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(builder.method(method,json==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(AgentFlowProperties.class)
    @EntityScan(basePackageClasses={AgentSessionEntity.class,SysUser.class,SessionContextEntity.class})
    @EnableJpaRepositories(basePackageClasses={AgentSessionRepository.class,SysUserRepository.class})
    @Import({SecurityConfig.class,AuthService.class,AuthController.class,JwtService.class,ContextHistoryController.class,ArtifactController.class,SessionDeletionController.class,RunApiExceptionHandler.class})
    static class Application {
        @Bean LocalArtifactStore artifacts(JdbcTemplate jdbc, PlatformTransactionManager transactions, Environment env) {
            return new LocalArtifactStore(jdbc, transactions, Path.of(env.getRequiredProperty("agentflow.harness.storage.data-root")), new Utf8TokenEstimator(), LocalArtifactStore.Limits.defaults());
        }
        @Bean ArtifactMaintenance maintenance(JdbcTemplate jdbc, PlatformTransactionManager transactions, LocalArtifactStore artifacts, Clock clock) {
            return new ArtifactMaintenance(jdbc, transactions, artifacts, clock);
        }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean RunCoordinator coordinator() { return mock(RunCoordinator.class); }
        @Bean RunApiErrorWriter errors(ObjectMapper mapper) { return new RunApiErrorWriter(mapper); }
        @Bean ContextHistoryService history(EntityManager manager,AgentSessionRepository sessions,RunCoordinator coordinator,Environment env,ObjectMapper mapper,Clock clock) {
            return new ContextHistoryService(manager,sessions,new ContextTextPolicy(),coordinator,env,mapper,clock);
        }
    }
}
