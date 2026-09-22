package com.agentflow.web.agent;

import com.agentflow.web.run.RunCoordinator;
import com.agentflow.web.run.RunResultProjector;
import com.agentflow.web.run.RunSnapshot;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AgentTaskService {
    private final RunCoordinator coordinator;
    public AgentTaskService(RunCoordinator coordinator) { this.coordinator = coordinator; }
    public RunCoordinator.RunAccepted create(String userId, String input) { return coordinator.create(userId, input); }
    public RunCoordinator.RunAccepted create(String userId, String input, String sessionId) { return coordinator.create(userId, input, sessionId); }
    public RunSnapshot getTask(String userId, String taskId) { return coordinator.getOwned(userId, taskId); }
    public List<RunResultProjector.ProjectedStep> getSteps(String userId, String taskId) {
        return coordinator.getOwnedSteps(userId, taskId);
    }
    public RunCoordinator.CancelReply cancel(String userId, String taskId) { return coordinator.cancel(userId, taskId); }
}
