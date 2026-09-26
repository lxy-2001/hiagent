package com.agentflow.web.run;

import com.agentflow.web.auth.AuthController;
import com.agentflow.web.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthenticationErrorContractTest {
    @Test
    void invalidCredentialsAndRefreshRemainUnauthorizedWithoutLeakingReason() throws Exception {
        AuthService service = mock(AuthService.class);
        when(service.login("demo", "wrong")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "private reason"));
        when(service.refresh("expired")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "private token reason"));
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
                .setControllerAdvice(new RunApiExceptionHandler()).build();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"expired\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
