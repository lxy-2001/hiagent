package com.agentflow.demo.workbench;

import com.agentflow.core.model.AgentModelClient;
import com.agentflow.demo.AgentFlowDemoApplication;
import com.agentflow.web.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AgentFlowDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:workbench-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true", "agentflow.knowledge.bootstrap.enabled=false",
        "agentflow.security.jwt.secret=workbench-fixture-secret-at-least-32-characters",
        "agentflow.model.chat-model=workbench-model", "agentflow.rag.enabled=false", "agentflow.mcp.enabled=false"})
abstract class WorkbenchHttpTestSupport {
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @MockitoBean AgentModelClient model;
    @MockitoBean StringRedisTemplate redis;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String token;

    @BeforeEach void prepareHttp() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.hasKey(anyString())).thenReturn(false);
        token = jwt.issueAccessToken("workbench-owner", "fixture-user", List.of("USER")).value();
    }

    HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (authenticated) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
