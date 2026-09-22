package com.agentflow.demo;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.demo.knowledge.KnowledgeService;
import com.agentflow.demo.tool.AgentToolRepository;
import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentTaskService;
import com.agentflow.web.auth.AuthController;
import com.agentflow.web.run.RunCoordinator;
import com.agentflow.web.run.RunLifecycleStatus;
import com.agentflow.web.auth.AuthService;
import com.agentflow.web.auth.SysUserRepository;
import com.agentflow.web.chat.ChatController;
import com.agentflow.web.chat.ChatRequest;
import com.agentflow.web.chat.ChatService;
import com.agentflow.web.chat.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration and basic-dispatch snapshot for the current, temporary Demo HTTP surface.
 * Stable Run/SSE semantics are intentionally deferred to a later feature.
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = AgentFlowDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:agentflow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "agentflow.knowledge.bootstrap.enabled=false",
        "agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32"
})
class TemporaryDemoEndpointSnapshotTest {

    private static final Set<Operation> EXPECTED_OPERATIONS = Set.of(
            new Operation("POST", "/api/auth/login"),
            new Operation("POST", "/api/auth/refresh"),
            new Operation("POST", "/api/auth/logout"),
            new Operation("GET", "/api/me"),
            new Operation("POST", "/api/chat"),
            new Operation("POST", "/api/chat/stream"),
            new Operation("POST", "/api/agent/tasks"),
            new Operation("GET", "/api/agent/tasks/{taskId}"),
            new Operation("GET", "/api/agent/tasks/{taskId}/steps"),
            new Operation("GET", "/api/agent/tasks/{taskId}/events"),
            new Operation("POST", "/api/agent/tasks/{taskId}/cancel"),
            new Operation("POST", "/api/knowledge/reload"),
            new Operation("GET", "/api/agent/sessions"),
            new Operation("GET", "/api/agent/sessions/{sessionId}"),
            new Operation("GET", "/api/agent/sessions/{sessionId}/turns"),
            new Operation("GET", "/api/agent/sessions/{sessionId}/memories"),
            new Operation("PUT", "/api/agent/sessions/{sessionId}/memories/{key}"),
            new Operation("DELETE", "/api/agent/sessions/{sessionId}/memories/{key}")
    );

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AgentTaskService agentTaskService;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private KnowledgeService knowledgeService;

    @MockitoBean
    private SysUserRepository sysUserRepository;

    @MockitoBean
    private AgentToolRepository agentToolRepository;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    @BeforeEach
    void setUpRedisAndResponses() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        when(authService.login("demo", "secret"))
                .thenReturn(new AuthController.TokenResponse("access", "refresh", 3600));
        when(chatService.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("session-1", "test", "test-model", "ok", TokenUsage.empty(), false));
        when(agentTaskService.create("user-1", "hello"))
                .thenReturn(new RunCoordinator.RunAccepted("task-1", "task-1", "session-1", RunLifecycleStatus.QUEUED));
        when(knowledgeService.reloadBuiltInKnowledge())
                .thenReturn(new KnowledgeService.ReloadResult(0, List.of()));
    }

    @Test
    void registersEighteenOperationsPreservingTheOriginalTwelve() {
        Set<Operation> actual = new HashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            Class<?> beanType = handler.getBeanType();
            if (!Set.of(AuthController.class, ChatController.class, AgentController.class,
                    com.agentflow.demo.knowledge.KnowledgeController.class,
                    com.agentflow.web.conversation.ConversationController.class,
                    com.agentflow.web.memory.ConfirmedMemoryController.class).contains(beanType)) {
                return;
            }
            for (String pattern : mapping.getPatternValues()) {
                for (RequestMethod method : mapping.getMethodsCondition().getMethods()) {
                    actual.add(new Operation(method.name(), pattern));
                }
            }
        });

        assertThat(actual).hasSize(18).containsExactlyInAnyOrderElementsOf(EXPECTED_OPERATIONS);
    }

    @Test
    void dispatchesOneControlledRequestForEachTemporaryEntryGroup() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"));

        mockMvc.perform(post("/api/chat")
                        .with(jwt().jwt(token -> token.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("ok"));

        mockMvc.perform(post("/api/agent/tasks")
                        .with(jwt().jwt(token -> token.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-1"));

        mockMvc.perform(post("/api/knowledge/reload")
                        .with(jwt().jwt(token -> token.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(0));
    }

    private record Operation(String method, String path) {
    }


}
