package com.agentflow.web.conversation;

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

@WebMvcTest(controllers = ConversationController.class, properties = "agentflow.security.jwt.secret=feature004-test-only-secret-at-least-32-bytes")
@Import({SecurityConfig.class, RunApiExceptionHandler.class, ConversationSecurityTest.WebSecurity.class})
@EnableConfigurationProperties(AgentFlowProperties.class)
@org.springframework.test.context.ContextConfiguration(classes = {ConversationController.class, SecurityConfig.class, RunApiExceptionHandler.class})
class ConversationSecurityTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class WebSecurity { }
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean ConversationService service;
    @MockitoBean StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    @BeforeEach void configureRedis() {
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.hasKey(anyString())).thenReturn(false);
    }
    private String token() {
        var claims = JwtClaimsSet.builder().subject("owner").id("test-jti").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    @Test void invalidAndRevokedTokensUseSharedNoStoreError() throws Exception {
        mvc.perform(get("/api/agent/sessions").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        when(redis.hasKey("auth:blacklist:test-jti")).thenReturn(true);
        mvc.perform(get("/api/agent/sessions/session").header("Authorization", "Bearer " + token()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }
    @Test void throttlingAndRedisFailuresHaveStableJsonWithoutSecretLeakage() throws Exception {
        when(values.increment(anyString())).thenReturn(121L);
        mvc.perform(get("/api/agent/sessions")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().string("Cache-Control", "no-store"));
        when(values.increment(anyString())).thenThrow(new IllegalStateException("password=private"));
        mvc.perform(get("/api/agent/sessions/session/turns")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        verifyNoInteractions(service);
    }
    @Test void ownershipAndBusinessFailuresAreNotMisclassifiedAsAuthenticationErrors() throws Exception {
        when(service.get("owner", "other")).thenThrow(new RunCoordinator.RunNotFoundException());
        mvc.perform(get("/api/agent/sessions/other").header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store"));
        when(service.turns("owner", "session", "0", null, 20)).thenThrow(new RunCoordinator.RunUnavailableException("SERVICE_RECOVERING", null));
        mvc.perform(get("/api/agent/sessions/session/turns").header("Authorization", "Bearer " + token()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_RECOVERING"));
    }
}
