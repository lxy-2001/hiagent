package com.agentflow.web.run;

import com.agentflow.web.agent.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunQueryApiTest {
    @Test void createReturnsAcceptedLocationAndQueuedReceipt() {
        AgentTaskService service=mock(AgentTaskService.class); when(service.create("owner","hello")).thenReturn(new RunCoordinator.RunAccepted("task","task","session",RunLifecycleStatus.QUEUED));
        AgentController controller=new AgentController(service);
        var response=controller.createTask(RunTestSupport.jwtFor("owner"),new AgentController.CreateTaskRequest("hello"));
        assertThat(response.getStatusCode().value()).isEqualTo(202); assertThat(response.getHeaders().getLocation().toString()).endsWith("/task");
        assertThat(response.getBody().status()).isEqualTo("QUEUED"); assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
