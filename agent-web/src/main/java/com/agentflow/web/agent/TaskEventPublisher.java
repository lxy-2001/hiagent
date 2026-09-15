package com.agentflow.web.agent;

import com.agentflow.core.AgentEvent;
import com.agentflow.web.run.RunEventHub;
import com.agentflow.web.run.RunEventProjector;

import java.time.Clock;
import java.util.Objects;

/** Compatibility sink that routes Core observations into the single run event window. */
public final class TaskEventPublisher {
    private final RunEventHub hub;
    private final RunEventProjector projector;
    private final Clock clock;

    public TaskEventPublisher(RunEventHub hub, RunEventProjector projector) {
        this(hub, projector, Clock.systemUTC());
    }

    TaskEventPublisher(RunEventHub hub, RunEventProjector projector, Clock clock) {
        this.hub = Objects.requireNonNull(hub);
        this.projector = Objects.requireNonNull(projector);
        this.clock = Objects.requireNonNull(clock);
    }

    public void publish(AgentEvent event) {
        if (event == null) return;
        projector.project(event.taskId(), event, clock.instant()).event()
                .ifPresent(draft -> hub.publish(event.taskId(), draft));
    }
}
