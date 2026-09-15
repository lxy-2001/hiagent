package com.agentflow.web.agent;

import com.agentflow.web.run.RunCoordinator;
import com.agentflow.web.run.RunLifecycleStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {
    @Test
    void delegatesCreationToTheLifecycleCoordinator() {
        RunCoordinator coordinator = mock(RunCoordinator.class);
        when(coordinator.create("user-1", "hello")).thenReturn(new RunCoordinator.RunAccepted(
                "task-1", "task-1", "session-1", RunLifecycleStatus.QUEUED));
        AgentTaskService service = new AgentTaskService(coordinator);

        RunCoordinator.RunAccepted result = service.create("user-1", "hello");

        assertThat(result.status()).isEqualTo(RunLifecycleStatus.QUEUED);
        verify(coordinator).create("user-1", "hello");
    }
}
