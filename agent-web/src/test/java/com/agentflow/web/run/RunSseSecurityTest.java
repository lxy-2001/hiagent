package com.agentflow.web.run;

import com.agentflow.web.agent.AgentController;
import com.agentflow.web.agent.AgentTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunSseSecurityTest {
    @Test
    void rejectsNonCanonicalAndOverflowingEventIdsWithoutOpeningASubscription() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        when(tasks.getTask("owner", "task")).thenReturn(RunSseHttpTest.terminal(
                java.time.Instant.parse("2026-09-15T00:00:00Z")));
        RunSseService sse = mock(RunSseService.class);
        AgentController controller = new AgentController(tasks, sse);

        for (String invalid : new String[]{"", "-1", "+1", "01", "1.0", "9223372036854775808", "1,2"}) {
            var response = controller.events(RunTestSupport.jwtFor("owner"), "task", invalid,
                    new MockHttpServletRequest());
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
        verifyNoInteractions(sse);
    }
}
