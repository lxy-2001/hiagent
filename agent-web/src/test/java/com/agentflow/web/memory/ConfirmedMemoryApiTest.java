package com.agentflow.web.memory;

import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfirmedMemoryApiTest {
    private final ConfirmedMemoryService service = mock(ConfirmedMemoryService.class);
    private final String url = "/api/agent/sessions/session/memories/project_stack";
    private MockMvc mvc() {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("owner").build();
        var resolver = new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
            public boolean supportsParameter(org.springframework.core.MethodParameter p) { return p.getParameterType() == Jwt.class; }
            public Object resolveArgument(org.springframework.core.MethodParameter p, org.springframework.web.method.support.ModelAndViewContainer m,
                    org.springframework.web.context.request.NativeWebRequest w, org.springframework.web.bind.support.WebDataBinderFactory b) { return jwt; }
        };
        return MockMvcBuilders.standaloneSetup(new ConfirmedMemoryController(service)).setControllerAdvice(new RunApiExceptionHandler())
                .setCustomArgumentResolvers(resolver).build();
    }
    @Test void rejectsUnknownFieldsNonStringVersionsUnknownKeysAndMalformedJson() throws Exception {
        var mvc = mvc();
        for (String body : java.util.List.of("{}", "{", "{\"value\":\"Java\",\"expectedVersion\":1}",
                "{\"value\":\"Java\",\"expectedVersion\":\"01\"}", "{\"value\":\"Java\",\"expectedVersion\":\"9223372036854775808\"}",
                "{\"value\":\"Java\",\"expectedVersion\":\"0\",\"source\":\"SYSTEM\"}", "{\"value\":123,\"expectedVersion\":\"0\"}")) {
            mvc.perform(put(url).contentType("application/json").content(body)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(put(url.replace("project_stack", "unknown")).contentType("application/json").content("{\"value\":\"Java\",\"expectedVersion\":\"0\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void preservesLargeDecimalVersionsAndReturnsDeletionTombstone() throws Exception {
        long version = 9007199254740993L;
        var slot = new ConfirmedMemoryService.Slot("session", "project_stack", "PROJECT_FACT", "DELETED", null, Long.toString(version), null, java.time.Instant.now());
        when(service.delete("owner", "session", "project_stack", version)).thenReturn(slot);
        mvc().perform(delete(url).param("expectedVersion", Long.toString(version))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.version").value(Long.toString(version)))
                .andExpect(jsonPath("$.state").value("DELETED"));
    }
    @Test void mapsConflictOverflowAndOwnershipToStableErrors() throws Exception {
        var mvc = mvc();
        when(service.put("owner", "session", "project_stack", "Java", 1)).thenThrow(new ConfirmedMemoryService.VersionConflictException());
        mvc.perform(put(url).contentType("application/json").content("{\"value\":\"Java\",\"expectedVersion\":\"1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMORY_VERSION_CONFLICT"));
        when(service.delete("owner", "session", "project_stack", 1)).thenThrow(new RunCoordinator.RunUnavailableException("MEMORY_VERSION_EXHAUSTED", null));
        mvc.perform(delete(url).param("expectedVersion", "1")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MEMORY_VERSION_EXHAUSTED"));
        when(service.get("owner", "session")).thenThrow(new RunCoordinator.RunNotFoundException());
        mvc.perform(get("/api/agent/sessions/session/memories")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
    }
}
