package com.agentflow.web.approval;

import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.web.config.SecurityConfig;
import com.agentflow.web.run.RunApiExceptionHandler;
import com.agentflow.web.run.RunCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ApprovalController.class, properties = "agentflow.security.jwt.secret=feature006-test-only-secret-at-least-32-bytes")
@Import({SecurityConfig.class, RunApiExceptionHandler.class, ApprovalSecurityContractTest.WebSecurity.class})
@EnableConfigurationProperties(AgentFlowProperties.class)
@org.springframework.test.context.ContextConfiguration(classes = {ApprovalController.class, SecurityConfig.class, RunApiExceptionHandler.class})
class ApprovalSecurityContractTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class WebSecurity { }
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean ApprovalService service;
    @MockitoBean StringRedisTemplate redis;
    @BeforeEach void configureRedis() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.hasKey(anyString())).thenReturn(false);
    }
    @Test void anonymousAndInvalidBearerCannotReadOrDecide() throws Exception {
        String path = "/api/agent/tasks/" + ApprovalFixtures.RUN + "/approvals";
        mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/" + java.util.UUID.randomUUID() + "/decision")
                .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
