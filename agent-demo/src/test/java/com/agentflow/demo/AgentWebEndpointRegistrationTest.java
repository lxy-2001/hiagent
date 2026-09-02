package com.agentflow.demo;

import com.agentflow.demo.knowledge.KnowledgeService;
import com.agentflow.demo.tool.AgentToolRepository;
import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentTaskService;
import com.agentflow.web.auth.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentflow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class AgentWebEndpointRegistrationTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentTaskService agentTaskService;

    @MockitoBean
    private SysUserRepository sysUserRepository;

    @MockitoBean
    private AgentToolRepository agentToolRepository;

    @MockitoBean
    private KnowledgeService knowledgeService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    @BeforeEach
    void setUpRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
    }

    @Test
    void registersAgentTaskCreationEndpointFromAgentWebModule() {
        boolean registered = handlerMapping.getHandlerMethods().entrySet().stream()
                .anyMatch(entry -> isAgentTaskCreationEndpoint(entry.getKey(), entry.getValue().getBeanType()));

        assertThat(registered).isTrue();
    }

    @Test
    void dispatchesAuthenticatedAgentTaskCreationRequest() throws Exception {
        when(agentTaskService.create("user-1", "hello"))
                .thenReturn(new AgentController.TaskResponse("task-1", "session-1", "RUNNING"));

        mockMvc.perform(post("/api/agent/tasks")
                        .with(jwt().jwt(token -> token.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    private boolean isAgentTaskCreationEndpoint(RequestMappingInfo mapping, Class<?> beanType) {
        return beanType == AgentController.class
                && mapping.getPatternValues().contains("/api/agent/tasks")
                && mapping.getMethodsCondition().getMethods().contains(RequestMethod.POST);
    }
}
