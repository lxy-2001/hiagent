package com.agentflow.web.agent;

import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.web.run.RunPersistence;
import com.agentflow.web.run.RunResultProjector;

import java.util.Objects;

public class JpaStepRecorder implements StepRecorder {

    private final RunPersistence persistence;
    private final RunResultProjector projector;

    public JpaStepRecorder(RunPersistence persistence, RunResultProjector projector) {
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.projector = Objects.requireNonNull(projector, "projector must not be null");
    }

    @Override
    public void record(AgentStepRecord step) {
        if (step == null) {
            return;
        }
        projector.projectStep(step.taskId(), step).ifPresent(persistence::recordStep);
    }
}
