package com.agentflow.demo;

import com.agentflow.demo.knowledge.KnowledgeService;
import com.agentflow.demo.tool.AgentToolRepository;
import com.agentflow.web.agent.AgentTaskService;
import com.agentflow.web.auth.AuthService;
import com.agentflow.web.auth.SysUserRepository;
import com.agentflow.web.chat.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@SpringBootTest(classes = AgentFlowDemoApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:agentflow-page;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "agentflow.knowledge.bootstrap.enabled=false",
        "agentflow.security.jwt.secret=test-only-jwt-secret-which-is-long-enough-32"
})
class RunDemoPageTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;
    @MockitoBean AgentTaskService taskService;
    @MockitoBean ChatService chatService;
    @MockitoBean KnowledgeService knowledgeService;
    @MockitoBean SysUserRepository users;
    @MockitoBean AgentToolRepository tools;
    @MockitoBean StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @BeforeEach void redisIsAvailable() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test void exposesTheRealObserverScriptButKeepsTaskRoutesAuthenticated() throws Exception {
        mvc.perform(get("/conversation.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(containsString("createConversationClient")));
        mvc.perform(get("/api/agent/sessions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/run-events.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(content().string(containsString("observeRun")));
        mvc.perform(get("/api/agent/tasks/task"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/run-events.js")))
                .andExpect(content().string(not(containsString("new EventSource"))));
    }
}
