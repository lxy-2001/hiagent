package com.agentflow.web.approval;

import com.agentflow.web.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApprovalApiContractTest {
    private final ApprovalService service = mock(ApprovalService.class);
    private final String base = "/api/agent/tasks/" + ApprovalFixtures.RUN + "/approvals";
    private MockMvc mvc() {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("owner").build();
        var resolver = new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
            public boolean supportsParameter(org.springframework.core.MethodParameter p) { return p.getParameterType() == Jwt.class; }
            public Object resolveArgument(org.springframework.core.MethodParameter p, org.springframework.web.method.support.ModelAndViewContainer m,
                    org.springframework.web.context.request.NativeWebRequest w, org.springframework.web.bind.support.WebDataBinderFactory b) { return jwt; }
        };
        return MockMvcBuilders.standaloneSetup(new ApprovalController(service)).setControllerAdvice(new RunApiExceptionHandler())
                .setCustomArgumentResolvers(resolver).build();
    }
    @Test void onlyDecisionIsAcceptedAndOwnerIsTakenFromJwt() throws Exception {
        var request = ApprovalFixtures.request();
        String url = base + "/" + request.approvalId() + "/decision";
        var mvc = mvc();
        for (String body : java.util.List.of("{}", "{", "{\"decision\":\"approve\"}",
                "{\"decision\":\"APPROVE\",\"arguments\":{}}", "{\"decision\":true}"))
            mvc.perform(post(url).contentType("application/json").content(body)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
        when(service.decide("owner", ApprovalFixtures.RUN, request.approvalId().toString(), ApprovalService.Decision.APPROVE))
                .thenReturn(ToolInvocationEntity.pending(request, "owner").snapshot());
        mvc.perform(post(url).contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.callId").value("call"));
    }
    @Test void listsOwnedApprovalsAndMapsConflictsAndUnknownOwner() throws Exception {
        when(service.list("owner", ApprovalFixtures.RUN)).thenReturn(java.util.List.of());
        mvc().perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        String id = java.util.UUID.randomUUID().toString();
        when(service.get("owner", ApprovalFixtures.RUN, id)).thenThrow(new RunCoordinator.RunNotFoundException());
        mvc().perform(get(base + "/" + id)).andExpect(status().isNotFound());
        when(service.decide("owner", ApprovalFixtures.RUN, id, ApprovalService.Decision.REJECT))
                .thenThrow(new ApprovalService.ConflictException("APPROVAL_ALREADY_DECIDED"));
        mvc().perform(post(base + "/" + id + "/decision").contentType("application/json").content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPROVAL_ALREADY_DECIDED"));
    }
}
