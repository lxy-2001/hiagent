package com.agentflow.demo.agent;

import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.step.StepRecorder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class JpaStepRecorder implements StepRecorder {

    private final AgentStepRepository stepRepository;

    public JpaStepRecorder(AgentStepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

    @Override
    public void record(AgentStepRecord step) {
        stepRepository.save(new AgentStepEntity(step));
    }
}
